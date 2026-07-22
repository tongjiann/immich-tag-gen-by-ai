package com.xiwang.phototagautogen.client;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
public final class HttpExecutor {
    private final HttpClient http1Client;
    private final HttpClient http2Client;
    private final int maxRetries;
    private final HttpClient.Version httpVersion;

    public HttpExecutor(int connectTimeoutSeconds, int maxRetries) {
        this(connectTimeoutSeconds, maxRetries, HttpClient.Version.HTTP_2);
    }

    public HttpExecutor(int connectTimeoutSeconds, int maxRetries, HttpClient.Version httpVersion) {
        this.httpVersion = httpVersion;
        this.http1Client = buildHttpClient(connectTimeoutSeconds, HttpClient.Version.HTTP_1_1);
        this.http2Client = buildHttpClient(connectTimeoutSeconds, HttpClient.Version.HTTP_2);
        this.maxRetries = maxRetries;
    }

    HttpClient.Version httpVersion() {
        return httpVersion;
    }

    HttpClient.Version fallbackHttpVersion() {
        return alternateVersion(httpVersion);
    }

    public HttpResponse<byte[]> execute(HttpRequest request) {
        int attempt = 0;
        HttpClient.Version selectedVersion = httpVersion;
        while (true) {
            long attemptStartedAt = System.nanoTime();
            try {
                HttpResponse<byte[]> response = httpClient(selectedVersion)
                        .send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response;
                }
                if (isRetryableStatus(response.statusCode()) && attempt < maxRetries) {
                    long waitMillis = backoffMillis(attempt);
                    log.warn("远程调用返回可重试状态，准备重试 method={}, path={}, status={}, "
                                    + "httpVersion={}, retry={}/{}, waitMs={}, elapsedMs={}",
                            request.method(), request.uri().getPath(), response.statusCode(), selectedVersion,
                            attempt + 1, maxRetries, waitMillis, elapsedMillis(attemptStartedAt));
                    sleep(waitMillis);
                    attempt++;
                    continue;
                }
                throw new RemoteCallException(buildErrorMessage(response), response.statusCode());
            } catch (IOException e) {
                String reason = describe(e);
                if (attempt >= maxRetries) {
                    throw new RemoteCallException(
                            buildConnectionErrorMessage(request, selectedVersion, attempt + 1,
                                    elapsedMillis(attemptStartedAt), reason), e);
                }
                HttpClient.Version nextVersion = alternateVersion(selectedVersion);
                long waitMillis = backoffMillis(attempt);
                log.warn("远程调用传输失败，切换 HTTP 协议后重试 method={}, target={}, httpVersion={}->{}, "
                                + "retry={}/{}, waitMs={}, elapsedMs={}, reason={}",
                        request.method(), requestTarget(request), selectedVersion, nextVersion,
                        attempt + 1, maxRetries, waitMillis, elapsedMillis(attemptStartedAt), reason);
                sleep(waitMillis);
                selectedVersion = nextVersion;
                attempt++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RemoteCallException("远程调用被中断", e);
            }
        }
    }

    private HttpClient buildHttpClient(int connectTimeoutSeconds, HttpClient.Version version) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .version(version)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private HttpClient httpClient(HttpClient.Version version) {
        return version == HttpClient.Version.HTTP_1_1 ? http1Client : http2Client;
    }

    private HttpClient.Version alternateVersion(HttpClient.Version version) {
        return version == HttpClient.Version.HTTP_1_1
                ? HttpClient.Version.HTTP_2
                : HttpClient.Version.HTTP_1_1;
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    private String buildConnectionErrorMessage(HttpRequest request, HttpClient.Version version,
                                               int attempts, long elapsedMillis, String reason) {
        return "远程服务连接失败 method=" + request.method()
                + ", target=" + requestTarget(request)
                + ", httpVersion=" + version
                + ", attempts=" + attempts
                + ", elapsedMs=" + elapsedMillis
                + ", reason=" + reason;
    }

    private String requestTarget(HttpRequest request) {
        URI uri = request.uri();
        String authority = uri.getRawAuthority();
        return uri.getScheme() + "://" + authority + uri.getRawPath();
    }

    private String describe(IOException exception) {
        String description = exceptionDescription(exception);
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        if (rootCause == exception) {
            return description;
        }
        return description + "; rootCause=" + exceptionDescription(rootCause);
    }

    private String exceptionDescription(Throwable exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private String buildErrorMessage(HttpResponse<byte[]> response) {
        String body = new String(response.body());
        if (body.length() > 500) {
            body = body.substring(0, 500);
        }
        return "远程服务返回 " + response.statusCode() + ": " + body;
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private long backoffMillis(int attempt) {
        return Math.min(8_000L, 500L << Math.min(attempt, 4));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteCallException("重试等待被中断", e);
        }
    }
}
