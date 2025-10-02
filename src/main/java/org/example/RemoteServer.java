package org.example;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;

public class RemoteServer {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/run", new RunHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Server started at http://localhost:8080");
    }

    static class RunHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            t.getResponseHeaders().add("Access-Control-Allow-Methods", "POST,OPTIONS");
            t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(204, -1);
                t.close();
                return;
            }

            try {
                String code;
                try (InputStream is = t.getRequestBody()) {
                    code = new String(is.readAllBytes());
                }

                File javaFile = new File("MyArt.java");
                try (FileWriter fw = new FileWriter(javaFile)) {
                    fw.write(code);
                }

                ProcessBuilder pb = new ProcessBuilder("javac", "MyArt.java");
                pb.redirectErrorStream(true);
                Process compile = pb.start();

                StringBuilder compileOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(compile.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        compileOutput.append(line).append("\n");
                    }
                }

                int exitCode = compile.waitFor();

                if (exitCode != 0) {
                    String msg = "{\"error\":\"Compilation failed:\\n" +
                            compileOutput.toString()
                                    .replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\n", "\\n") +
                            "\"}";
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    t.sendResponseHeaders(400, msg.getBytes().length);
                    t.getResponseBody().write(msg.getBytes());
                    t.close();
                    return;
                }

                // fillPixels
                URLClassLoader classLoader = URLClassLoader.newInstance(new URL[]{new File(".").toURI().toURL()});
                Class<?> clazz = Class.forName("MyArt", true, classLoader);
                Method method = clazz.getMethod("fillPixels", int.class, int.class);

                int width = 128;
                int height = 128;
                int[][][] pixels = (int[][][]) method.invoke(null, width, height);

                //JSON
                StringBuilder json = new StringBuilder("{\"pixels\":[");
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int[] p = pixels[y][x];
                        json.append(String.format(
                                "{\"x\":%d,\"y\":%d,\"r\":%d,\"g\":%d,\"b\":%d},",
                                x, y, p[0], p[1], p[2]));
                    }
                }
                if (json.charAt(json.length() - 1) == ',') json.setLength(json.length() - 1);
                json.append("]}");

                byte[] response = json.toString().getBytes();
                t.getResponseHeaders().add("Content-Type", "application/json");
                t.sendResponseHeaders(200, response.length);
                t.getResponseBody().write(response);
                t.close();

            } catch (Exception e) {
                e.printStackTrace();
                String msg = "{\"error\":\"" + e.getMessage()
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n") + "\"}";
                t.getResponseHeaders().add("Content-Type", "application/json");
                t.sendResponseHeaders(500, msg.getBytes().length);
                t.getResponseBody().write(msg.getBytes());
                t.close();
            }
        }
    }
}
