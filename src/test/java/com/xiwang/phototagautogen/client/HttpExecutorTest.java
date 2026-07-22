package com.xiwang.phototagautogen.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpExecutorTest {

    @Test
    void OpenAI兼容接口应优先使用HTTP11并准备HTTP2作为传输失败降级协议() {
        HttpExecutor executor = new HttpExecutor(1, 0, HttpClient.Version.HTTP_1_1);

        assertThat(executor.httpVersion()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(executor.fallbackHttpVersion()).isEqualTo(HttpClient.Version.HTTP_2);
    }

    @Test
    void 分块响应被重置后应切换协议并重试() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread serverThread = Thread.ofVirtual().start(() -> {
                try {
                    respondWithIncompleteChunk(serverSocket.accept(), requestCount);
                    respondSuccessfully(serverSocket.accept(), requestCount);
                } catch (Throwable e) {
                    serverFailure.set(e);
                }
            });
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + serverSocket.getLocalPort() + "/v1/chat/completions"))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = new HttpExecutor(2, 1, HttpClient.Version.HTTP_1_1)
                    .execute(request);

            serverThread.join();
            assertThat(serverFailure.get()).isNull();
            assertThat(requestCount.get()).isEqualTo(2);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("ok");
        }
    }

    @Test
    void 连接失败时错误信息应包含目标地址和底层原因() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            unusedPort = socket.getLocalPort();
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + unusedPort + "/v1/chat/completions"))
                .GET()
                .build();

        assertThatThrownBy(() -> new HttpExecutor(1, 0).execute(request))
                .isInstanceOf(RemoteCallException.class)
                .hasMessageContaining("远程服务连接失败")
                .hasMessageContaining("target=http://127.0.0.1:" + unusedPort + "/v1/chat/completions")
                .hasMessageContaining("attempts=1")
                .hasMessageContaining(ConnectException.class.getSimpleName());
    }

    private void respondWithIncompleteChunk(Socket socket, AtomicInteger requestCount) throws IOException {
        try (socket) {
            readRequestHeaders(socket.getInputStream());
            requestCount.incrementAndGet();
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n"
                    + "2\r\nok\r\n").getBytes(StandardCharsets.US_ASCII));
        }
    }

    private void respondSuccessfully(Socket socket, AtomicInteger requestCount) throws IOException {
        try (socket) {
            readRequestHeaders(socket.getInputStream());
            requestCount.incrementAndGet();
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: 2\r\n"
                    + "Connection: close\r\n\r\n"
                    + "ok").getBytes(StandardCharsets.US_ASCII));
        }
    }

    private void readRequestHeaders(InputStream input) throws IOException {
        int matched = 0;
        int value;
        byte[] terminator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        while (matched < terminator.length && (value = input.read()) != -1) {
            matched = value == terminator[matched] ? matched + 1 : 0;
        }
    }
}
