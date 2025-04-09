package br.com.yurizp.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class MetricAspect {

    private static final String METRIC_SUFIX_TIME = ".time";
    private final static String ERROR_STATUS = "errorStatus";
    private final static String ERROR_TITLE = "errorTitle";
    private final static String ERROR_DETAIL = "errorDetail";
    private final static String ERROR_TYPE = "erroType";
    private final MeterRegistry meterRegistry;
    private final MessageSource messageSource;

    @Around("@annotation(metric)")
    public Object timedMetric(ProceedingJoinPoint joinPoint, Metric metric) throws Throwable {
        var startTime = nanoTime();

        try {
            Object result = joinPoint.proceed();
            meterRegistry(joinPoint, null, startTime, metric.name());
            return result;
        } catch (Throwable ex) {
            meterRegistry(joinPoint, ex, startTime, metric.name());
            throw ex;
        }
    }

    private void meterRegistry(ProceedingJoinPoint joinPoint, Throwable ex, long startTime, String metricName) {
        try {
            List<Tag> tags = extractTags(joinPoint.getArgs(), ex);
            var duration = NANOSECONDS.toMillis(nanoTime() - startTime);
            meterRegistry.counter(metricName, tags).increment();
            meterRegistry.timer(metricName.concat(METRIC_SUFIX_TIME), tags).record(duration, MILLISECONDS);
            log.info("Metrica registrada com sucesso: {} - {}ms", metricName, duration);
        } catch (Exception e) {
            log.error("Erro ao registrar a metrica", e);
        }
    }

    private List<Tag> extractTags(Object[] args, Throwable error) {
        Map<String, String> defaultTags = new HashMap<>(extractTagsFromError(error));

        for (Object arg : args) {
            Arrays.stream(arg.getClass().getDeclaredFields())
                    .filter(field -> field.isAnnotationPresent(MetricProperty.class))
                    .forEach(field -> {
                        String key = getKeyName(field);
                        String value = getValue(field, arg);
                        defaultTags.put(key, value);
                    });
        }
        return convertMapToTag(defaultTags);
    }

    private List<Tag> convertMapToTag(Map<String, String> tags) {
        return tags.entrySet().stream()
                .map(entry -> Tag.of(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String getValue(Field field, Object arg) {
        field.setAccessible(true);
        try {
            Object value = field.get(arg);
            return Objects.toString(value);
        } catch (IllegalAccessException e) {
            log.error("Erro ao obter o valor das metricas", e);
        }
        return StringUtils.EMPTY;
    }

    private static String getKeyName(Field field) {
        return Optional.ofNullable(field.getAnnotation(MetricProperty.class))
                .map(MetricProperty::name)
                .filter(StringUtils::isNotBlank)
                .orElseGet(field::getName);
    }

    private Map<String, String> extractTagsFromError(Throwable error) {
        String errorStatus = "500";
        String errorTitle = "";
        String errorDetail = "";
        String errorType = "technical_error";

        if (error instanceof ErrorResponse baseError) {
            ProblemDetail problemDetail = baseError.updateAndGetBody(messageSource, Locale.getDefault());
            errorTitle = getProblemDetailTitle(problemDetail);
            errorStatus = getProblemDetailStatus(problemDetail);
            errorDetail = getProblemDetailDetail(problemDetail);
            errorType = getErrorType(problemDetail);
        } else if (Objects.nonNull(error)) {
            String message = error.getMessage();
            errorTitle = message;
            errorStatus = message;
        }

        return Map.of(
                ERROR_TYPE, errorType,
                ERROR_STATUS, errorStatus,
                ERROR_TITLE, errorTitle,
                ERROR_DETAIL, errorDetail
        );
    }

    private String getErrorType(ProblemDetail exception) {
        boolean isBusinessError = Optional.ofNullable(exception)
                .map(ProblemDetail::getStatus)
                .map(HttpStatusCode::valueOf)
                .map(HttpStatusCode::is4xxClientError)
                .orElse(false);
        return isBusinessError ? "business_error" : "technical_error";
    }

    private String getProblemDetailTitle(ProblemDetail exception) {
        return Optional.ofNullable(exception)
                .map(ProblemDetail::getTitle)
                .orElse(StringUtils.EMPTY);
    }

    private String getProblemDetailDetail(ProblemDetail exception) {
        return Optional.ofNullable(exception)
                .map(ProblemDetail::getTitle)
                .orElse(StringUtils.EMPTY);
    }


    private String getProblemDetailStatus(ProblemDetail exception) {
        return Optional.ofNullable(exception)
                .map(ProblemDetail::getStatus)
                .map(Objects::toString)
                .orElse(StringUtils.EMPTY);
    }


}
