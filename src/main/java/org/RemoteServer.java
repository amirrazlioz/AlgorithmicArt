package org;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.net.URI;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


public class RemoteServer {
	
	private static final List<String> onlineUsers = new CopyOnWriteArrayList<>();
    private static final AtomicInteger totalRequestsCounter = new AtomicInteger(0);
    private static final Map<String, String> studentNames = new ConcurrentHashMap<>();
    // private static final Map<String, AtomicInteger> userRunCounts = new ConcurrentHashMap<>();

	// מפה שמחזיקה לכל IP מפה פנימית של מונים (לפי שם ה-Handler)
	private static final Map<String, Map<String, Integer>> userActivityStats = new ConcurrentHashMap<>();
	
	// במקום או בנוסף ל-onlineUsers, נשתמש בזה כדי לעקוב אחרי זמן:
	private static final Map<String, Long> lastSeenMap = new ConcurrentHashMap<>();
	
	// IP -> (TaskName -> Rating)
	private static final Map<String, Map<String, Integer>> feedbackRatings = new ConcurrentHashMap<>();
	
	private static boolean creativeEnabled = true; // ברירת מחדל - פתוח

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // שימוש ב-Thread Pool לביצועים
        server.setExecutor(Executors.newFixedThreadPool(20));

        // --- נתיב 1: דף הבית (index.html) ---
        server.createContext("/", exchange -> {
            String ip = getClientIp(exchange); // שימוש בפונקציה המתוקנת
            if (!onlineUsers.contains(ip)) onlineUsers.add(ip);
            
            try (InputStream is = RemoteServer.class.getClassLoader().getResourceAsStream("index.html")) {
                if (is == null) {
                    sendTextResponse(exchange, 404, "File not found");
                } else {
                    byte[] bytes = is.readAllBytes();
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } finally {
                exchange.close();
            }
        });

        // --- נתיב 2: דף ניהול (admin.html) ---
        server.createContext("/admin", exchange -> {
            try (InputStream is = RemoteServer.class.getClassLoader().getResourceAsStream("admin.html")) {
                if (is == null) {
                    sendTextResponse(exchange, 404, "Admin page not found");
                } else {
                    byte[] bytes = is.readAllBytes();
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } finally {
                exchange.close();
            }
        });

		// --- נתיב חדש: נתונים ל-Dashboard הגרפי ---
		server.createContext("/api/admin-stats", new AdminStatsHandler());
		
		// --- נתיב חדש: רישום שם תלמיד ---
		server.createContext("/api/register", new RegisterHandler());
		server.createContext("/api/reset-all", new ResetAllHandler());
		server.createContext("/api/feedback", new FeedbackHandler());
		server.createContext("/api/settings", new SettingsHandler());
		
		// נתיבי ההרצה הקיימים שלך
		server.createContext("/run1", new RunHandler1());
		server.createContext("/run2", new RunHandler2());
		server.createContext("/run3", new RunHandler3());
		server.createContext("/run4", new RunHandler4());
		server.createContext("/run5", new RunHandler5());
		server.createContext("/run6", new RunHandler6());
		server.createContext("/run-creative", new RunCreative());

		// 4. הפעלת השרת
		server.start();
		// System.out.println("Server is running on http://localhost:" + port);
		System.out.println("Server started on port " + port);
	}
	
	// --- כאן כדאי להוסיף את המתודה החדשה ---
    private static Connection getConnection() throws Exception {
        String dbUrl = System.getenv("DATABASE_URL");
        if (dbUrl == null) throw new RuntimeException("DATABASE_URL is missing!");
        
        URI dbUri = new URI(dbUrl);
        String username = dbUri.getUserInfo().split(":")[0];
        String password = dbUri.getUserInfo().split(":")[1];
        String dbPath = "jdbc:postgresql://" + dbUri.getHost() + ":" + dbUri.getPort() + dbUri.getPath();
        
        return DriverManager.getConnection(dbPath, username, password);
    }
	
	
	// 1. פונקציית עזר לזיהוי IP - קריטי ל-Railway!
    private static String getClientIp(HttpExchange t) {
        // בדיקת Header של Proxy (נפוץ ב-Railway)
        String ip = t.getRequestHeaders().getFirst("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            return t.getRemoteAddress().getAddress().getHostAddress();
        }
        // X-Forwarded-For יכול להכיל רשימה, אנחנו לוקחים את ה-IP הראשון
        return ip.split(",")[0].trim();
    }

    // 2. פונקציית עזר לעדכון המונים של המשתמש
	private static void incrementUserStat(String ip, String action) {
		// action יהיה "run1", "run2", "run-creative" וכו'
		userActivityStats.computeIfAbsent(ip, k -> new ConcurrentHashMap<>())
						 .merge(action, 1, Integer::sum);
	}
	
	// פונקציית עזר לשליחת תגובת טקסט פשוטה (שמונעת את שגיאת ה-Symbol)
    private static void sendTextResponse(HttpExchange t, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = t.getResponseBody()) {
            os.write(bytes);
        }
    }
	
	private static JsonObject executeStudentCodeImage(String studentCode, int[][] image, String wrapperMethodName) throws Exception {		
		String uniqueId = "u" + java.util.UUID.randomUUID().toString().replace("-", "");	
		String className = "DynamicClass_" + uniqueId;
		File requestDir = new File("temp_build/" + uniqueId);
		if (!requestDir.exists()) requestDir.mkdirs();
		
		File javaFile = new File(requestDir, className + ".java");
		
		final int[][][] resultHolder = new int[1][][];
		final String[] logHolder = new String[1];
		
		try {
			String classCode = "package " + uniqueId + ";\n" +
							   "public class " + className + " {\n" +
							   "    public static int[][] run(int[][] image) {\n" +
							   "        return " + wrapperMethodName + "(image);\n" +
							   "    }\n" +
							   studentCode + "\n" +
							   "}";
							   
			Files.write(javaFile.toPath(), classCode.getBytes(StandardCharsets.UTF_8));

			ProcessBuilder pb = new ProcessBuilder("javac", "-d", requestDir.getPath(), javaFile.getPath());
			pb.redirectErrorStream(true);
			Process compile = pb.start();
			
			StringBuilder compileOutput = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(compile.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) compileOutput.append(line).append("\n");
			}

			if (compile.waitFor() != 0) {
				throw new Exception("Compilation failed:\n" + compileOutput.toString());
			}

			URL[] urls = { requestDir.toURI().toURL() };
			try (URLClassLoader loader = new URLClassLoader(urls)) {
				Class<?> cls = Class.forName(uniqueId + "." + className, true, loader);
				Method method = cls.getMethod("run", int[][].class);

				ExecutorService executor = Executors.newSingleThreadExecutor();
				try {
					Future<?> future = executor.submit(() -> {
						try {
							synchronized (System.out) {
								PrintStream originalOut = System.out;
								ByteArrayOutputStream baos = new ByteArrayOutputStream();
								try (PrintStream newOut = new PrintStream(baos)) {
									System.setOut(newOut);
									
									// הרצת קוד התלמיד
									resultHolder[0] = (int[][]) method.invoke(null, (Object) image);
									
									System.out.flush();
								} finally {
									System.setOut(originalOut);
								}
								logHolder[0] = baos.toString(StandardCharsets.UTF_8);
							}
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					});

					future.get(5, java.util.concurrent.TimeUnit.SECONDS);
					
				} catch (java.util.concurrent.TimeoutException e) {
					throw new Exception("Code execution timed out! (Possible infinite loop)");
				} finally {
					executor.shutdownNow(); 
				}
			}

			JsonObject result = new JsonObject();
			result.add("image", new Gson().toJsonTree(resultHolder[0]));
			result.addProperty("consoleOutput", logHolder[0]);
			return result;

		} finally {
			deleteDirectory(requestDir);
		}
	}
	
	private static JsonObject executeStudentCodeInt(String studentCode, int[][] image, String wrapperMethodName) throws Exception {		
		String uniqueId = "u" + java.util.UUID.randomUUID().toString().replace("-", "");	
		String className = "DynamicClass_" + uniqueId;
		File requestDir = new File("temp_build/" + uniqueId);
		if (!requestDir.exists()) requestDir.mkdirs();
		
		final Number[] resultHolder = new Number[1];
		final String[] logHolder = new String[1];
		
		File javaFile = new File(requestDir, className + ".java");
		
		try {
			String classCode = "package " + uniqueId + ";\n" +
							   "public class " + className + " {\n" +
							   "    public static int run(int[][] image) {\n" +
							   "        return " + wrapperMethodName + "(image);\n" +
							   "    }\n" +
							   studentCode + "\n" +
							   "}";           
							   
			Files.write(javaFile.toPath(), classCode.getBytes(StandardCharsets.UTF_8));

			ProcessBuilder pb = new ProcessBuilder("javac", "-d", requestDir.getPath(), javaFile.getPath());
			pb.redirectErrorStream(true);
			Process compile = pb.start();
			
			StringBuilder compileOutput = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(compile.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) compileOutput.append(line).append("\n");
			}

			if (compile.waitFor() != 0) {
				throw new Exception("Compilation failed:\n" + compileOutput.toString());
			}

			URL[] urls = { requestDir.toURI().toURL() };
			try (URLClassLoader loader = new URLClassLoader(urls)) {
				Class<?> cls = Class.forName(uniqueId + "." + className, true, loader);
				Method method = cls.getMethod("run", int[][].class);

				ExecutorService executor = Executors.newSingleThreadExecutor();
				try {
					Future<?> future = executor.submit(() -> {
						try {
							synchronized (System.out) {
								PrintStream originalOut = System.out;
								ByteArrayOutputStream baos = new ByteArrayOutputStream();
								try (PrintStream newOut = new PrintStream(baos)) {
									System.setOut(newOut);
									
									resultHolder[0] = (Number) method.invoke(null, (Object) image);
									
									System.out.flush();
								} finally {
									System.setOut(originalOut);
								}
								logHolder[0] = baos.toString(StandardCharsets.UTF_8);
							}
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					});

					future.get(5, java.util.concurrent.TimeUnit.SECONDS);
					
				} catch (java.util.concurrent.TimeoutException e) {
					throw new Exception("Code execution timed out! (Possible infinite loop)");
				} finally {
					executor.shutdownNow(); // עצירת ה-Thread של התלמיד
				}
			}

			JsonObject response = new JsonObject();
			response.addProperty("result", resultHolder[0]);
			response.addProperty("consoleOutput", logHolder[0]);
			return response;

		} finally {
			deleteDirectory(requestDir);
		}
	}

	private static JsonObject executeStudentCodeRepPixel(String studentCode, int[][] image, int r, int c, int count, String wrapperMethodName) throws Exception {		
		String uniqueId = "u" + java.util.UUID.randomUUID().toString().replace("-", "");	
		String className = "DynamicClass_" + uniqueId;
		File requestDir = new File("temp_build/" + uniqueId);
		if (!requestDir.exists()) requestDir.mkdirs();
		
		File javaFile = new File(requestDir, className + ".java");
		
		final int[][][] resultHolder = new int[1][][];
		final String[] logHolder = new String[1];
		
		try {
			String classCode = "package " + uniqueId + ";\n" +
							   "public class " + className + " {\n" +
							   "    public static int[][] run(int[][] image, int r, int c, int count) {\n" +
							   "        return " + wrapperMethodName + "(image, r, c, count);\n" +
							   "    }\n" +
							   studentCode + "\n" +
							   "}";       
							   
			Files.write(javaFile.toPath(), classCode.getBytes(StandardCharsets.UTF_8));

			ProcessBuilder pb = new ProcessBuilder("javac", "-d", requestDir.getPath(), javaFile.getPath());
			pb.redirectErrorStream(true);
			Process compile = pb.start();
			
			StringBuilder compileOutput = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(compile.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) compileOutput.append(line).append("\n");
			}

			if (compile.waitFor() != 0) {
				throw new Exception("Compilation failed:\n" + compileOutput.toString());
			}

			URL[] urls = { requestDir.toURI().toURL() };
			try (URLClassLoader loader = new URLClassLoader(urls)) {
				Class<?> cls = Class.forName(uniqueId + "." + className, true, loader);
				// הגדרת הפרמטרים של המתודה: מערך דו-מימדי ושלושה אינטים
				Method method = cls.getMethod("run", int[][].class, int.class, int.class, int.class);

				ExecutorService executor = Executors.newSingleThreadExecutor();
				try {
					Future<?> future = executor.submit(() -> {
						try {
							synchronized (System.out) {
								PrintStream originalOut = System.out;
								ByteArrayOutputStream baos = new ByteArrayOutputStream();
								try (PrintStream newOut = new PrintStream(baos)) {
									System.setOut(newOut);
									
									// הרצה עם הפרמטרים r, c, count
									resultHolder[0] = (int[][]) method.invoke(null, (Object) image, r, c, count);
									
									System.out.flush();
								} finally {
									System.setOut(originalOut);
								}
								logHolder[0] = baos.toString(StandardCharsets.UTF_8);
							}
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					});

					future.get(5, java.util.concurrent.TimeUnit.SECONDS);
					
				} catch (java.util.concurrent.TimeoutException e) {
					throw new Exception("Code execution timed out! (Possible infinite loop or very slow code)");
				} finally {
					executor.shutdownNow(); // עצירת ה-Thread במידה וחרג מהזמן
				}
			}

			JsonObject result = new JsonObject();
			result.add("image", new Gson().toJsonTree(resultHolder[0]));
			result.addProperty("consoleOutput", logHolder[0]);
			return result;

		} finally {
			deleteDirectory(requestDir);
		}
	}

	private static JsonObject executeStudentCodeRepRec(String studentCode, int[][] image, int r, int c, int rCount, int cCount, String wrapperMethodName) throws Exception {		
		String uniqueId = "u" + java.util.UUID.randomUUID().toString().replace("-", "");	
		String className = "DynamicClass_" + uniqueId;
		File requestDir = new File("temp_build/" + uniqueId);
		if (!requestDir.exists()) requestDir.mkdirs();
		
		File javaFile = new File(requestDir, className + ".java");
		
		final int[][][] resultHolder = new int[1][][];
		final String[] logHolder = new String[1];
		
		try {
			String classCode = "package " + uniqueId + ";\n" +
							   "public class " + className + " {\n" +
							   "    public static int[][] run(int[][] image, int r, int c, int rCount, int cCount) {\n" +
							   "        return " + wrapperMethodName + "(image, r, c, rCount, cCount);\n" +
							   "    }\n" +
							   studentCode + "\n" +
							   "}";       
							   
			Files.write(javaFile.toPath(), classCode.getBytes(StandardCharsets.UTF_8));

			ProcessBuilder pb = new ProcessBuilder("javac", "-d", requestDir.getPath(), javaFile.getPath());
			pb.redirectErrorStream(true);
			Process compile = pb.start();
			
			StringBuilder compileOutput = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(compile.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) compileOutput.append(line).append("\n");
			}

			if (compile.waitFor() != 0) {
				throw new Exception("Compilation failed:\n" + compileOutput.toString());
			}

			URL[] urls = { requestDir.toURI().toURL() };
			try (URLClassLoader loader = new URLClassLoader(urls)) {
				Class<?> cls = Class.forName(uniqueId + "." + className, true, loader);
				Method method = cls.getMethod("run", int[][].class, int.class, int.class, int.class, int.class);

				ExecutorService executor = Executors.newSingleThreadExecutor();
				try {
					Future<?> future = executor.submit(() -> {
						try {
							synchronized (System.out) {
								PrintStream originalOut = System.out;
								ByteArrayOutputStream baos = new ByteArrayOutputStream();
								try (PrintStream newOut = new PrintStream(baos)) {
									System.setOut(newOut);
									
									resultHolder[0] = (int[][]) method.invoke(null, (Object) image, r, c, rCount, cCount);
									
									System.out.flush();
								} finally {
									System.setOut(originalOut);
								}
								logHolder[0] = baos.toString(StandardCharsets.UTF_8);
							}
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					});

					future.get(5, java.util.concurrent.TimeUnit.SECONDS);
					
				} catch (java.util.concurrent.TimeoutException e) {
					throw new Exception("Code execution timed out! (Possible infinite loop in rectangle logic)");
				} finally {
					executor.shutdownNow(); // עצירת ה-Thread מיד בסיום או בטיימאאוט
				}
			}

			JsonObject result = new JsonObject();
			result.add("image", new Gson().toJsonTree(resultHolder[0]));
			result.addProperty("consoleOutput", logHolder[0]);
			return result;

		} finally {
			deleteDirectory(requestDir);
		}
	}

	private static void deleteDirectory(File dir) {
		File[] files = dir.listFiles();
		if (files != null) {
			for (File f : files) f.delete();
		}
		dir.delete();
	}
	
	private static void updateTaskInDB(String ip, String taskName) {
		try (Connection conn = getConnection()) {
			// השאילתה מעדכנת את סך ההרצות הכללי ואת המונה הספציפי בתוך ה-JSON
			String sql = "UPDATE students SET " +
						 "total_runs = total_runs + 1, " +
						 "last_seen = ?, " +
						 "run_counts = jsonb_set(COALESCE(run_counts, '{}'), ARRAY[?], " +
						 "(COALESCE(run_counts->>?, '0')::int + 1)::text::jsonb) " +
						 "WHERE ip = ?";
			
			try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
				long now = System.currentTimeMillis();
				pstmt.setLong(1, now);
				pstmt.setString(2, taskName);
				pstmt.setString(3, taskName);
				pstmt.setString(4, ip);
				pstmt.executeUpdate();
			}
		} catch (Exception e) {
			System.err.println("Error updating DB for " + taskName + ": " + e.getMessage());
		}
	}

	static class RunHandler1 implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {		
			String ip = getClientIp(t);
			incrementUserStat(ip, "run1"); 
			totalRequestsCounter.incrementAndGet();
			lastSeenMap.put(ip, System.currentTimeMillis());
			
			// אנחנו שולחים את ה-IP ואת שם המשימה "run1"
            updateTaskInDB(ip, "run1");
			
			// הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();
			try (InputStream is = t.getRequestBody()) {
					String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
					JsonObject request = gson.fromJson(body, JsonObject.class);
					
					String code = request.get("code").getAsString();
					int[][] image = gson.fromJson(request.get("image"), int[][].class);

					JsonObject responseJson = executeStudentCodeImage(code, image, "addFrame");
					
					byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
					t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
					t.sendResponseHeaders(200, b.length);
					
					// שימוש ב-try-with-resources כדי לוודא שהתשובה נשלחת ונסגרת
					try (OutputStream os = t.getResponseBody()) {
						os.write(b);
					}
			} catch (Exception e) {
				// טיפול בשגיאות (קומפילציה או ריצה)
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				t.getResponseBody().write(b);
			} finally {
				t.close();
			}
		}
	}	

	static class RunHandler2 implements HttpHandler {	
		public void handle(HttpExchange t) throws IOException {
			String ip = getClientIp(t);
			incrementUserStat(ip, "run2"); 
			totalRequestsCounter.incrementAndGet();
			lastSeenMap.put(ip, System.currentTimeMillis());
			
			// אנחנו שולחים את ה-IP ואת שם המשימה "run2"
            updateTaskInDB(ip, "run2");
			
			// הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject request = gson.fromJson(body, JsonObject.class);
				
				String code = request.get("code").getAsString();
				int[][] image = gson.fromJson(request.get("image"), int[][].class);

				// שימוש בפונקציה המרכזית!
				JsonObject responseJson = executeStudentCodeImage(code, image, "createDiagonal");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				// שימוש ב-try-with-resources כדי לוודא שהתשובה נשלחת ונסגרת
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}				
			} catch (Exception e) {
				// טיפול בשגיאות (קומפילציה או ריצה)
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				t.getResponseBody().write(b);
			} finally {
				t.close();
			}
		}
	}

	static class RunHandler3 implements HttpHandler {	
		public void handle(HttpExchange t) throws IOException {
			String ip = getClientIp(t);
			incrementUserStat(ip, "run3"); 
			totalRequestsCounter.incrementAndGet();
			lastSeenMap.put(ip, System.currentTimeMillis());
			
			// אנחנו שולחים את ה-IP ואת שם המשימה "run3"
            updateTaskInDB(ip, "run3");
			
			// הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject request = gson.fromJson(body, JsonObject.class);
				
				String code = request.get("code").getAsString();
				int[][] image = gson.fromJson(request.get("image"), int[][].class);

				// שימוש בפונקציה המרכזית!
				JsonObject responseJson = executeStudentCodeInt(code, image, "findMaxDiagonal");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				// שימוש ב-try-with-resources כדי לוודא שהתשובה נשלחת ונסגרת
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} catch (Exception e) {
				// טיפול בשגיאות (קומפילציה או ריצה)
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				t.getResponseBody().write(b);
			} finally {
				t.close();
			}
		}
	}

	static class RunHandler4 implements HttpHandler {	
		public void handle(HttpExchange t) throws IOException {
			String ip = getClientIp(t);
			incrementUserStat(ip, "run4"); 
			totalRequestsCounter.incrementAndGet();
			lastSeenMap.put(ip, System.currentTimeMillis());
			
			// אנחנו שולחים את ה-IP ואת שם המשימה "run4"
            updateTaskInDB(ip, "run4");
			
			// הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject request = gson.fromJson(body, JsonObject.class);
				
				String code = request.get("code").getAsString();
				int[][] image = gson.fromJson(request.get("image"), int[][].class);

				// שימוש בפונקציה המרכזית!
				JsonObject responseJson = executeStudentCodeInt(code, image, "findMinSecondaryDiagonal");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				// שימוש ב-try-with-resources כדי לוודא שהתשובה נשלחת ונסגרת
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} catch (Exception e) {
				// טיפול בשגיאות (קומפילציה או ריצה)
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				t.getResponseBody().write(b);
			} finally {
				t.close();
			}
		}
	}

	static class RunHandler5 implements HttpHandler {	
		public void handle(HttpExchange t) throws IOException {
			String ip = getClientIp(t);
			incrementUserStat(ip, "run5"); 
			totalRequestsCounter.incrementAndGet();
			lastSeenMap.put(ip, System.currentTimeMillis());
			
			// אנחנו שולחים את ה-IP ואת שם המשימה "run5"
            updateTaskInDB(ip, "run5");
			
			// הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				
				
				JsonObject req = gson.fromJson(body, JsonObject.class);
					String code = req.get("code").getAsString();
					int[][] image = gson.fromJson(req.get("image"), int[][].class);
					int countToReplicate = req.has("count") ? req.get("count").getAsInt() : 1;

					// 2. חישוב המיקום הראשון שאינו רקע (לוגיקה שלך)
					int rPos = 0, cPos = 0;
					boolean found = false;
					for (int r = 0; r < image.length && !found; r++) {
						for (int c = 0; c < image[r].length; c++) {
							if (image[r][c] != 1) { // מניח ש-1 הוא צבע הרקע
								rPos = r; cPos = c;
								found = true;
								break;
							}
						}
					}
				
				// שימוש בפונקציה המרכזית!
				JsonObject responseJson = executeStudentCodeRepPixel(code, image, rPos, cPos, countToReplicate, "replicatePixel");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				// שימוש ב-try-with-resources כדי לוודא שהתשובה נשלחת ונסגרת
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}			
			} catch (Exception e) {
				// טיפול בשגיאות (קומפילציה או ריצה)
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				t.getResponseBody().write(b);
			} finally {
				t.close();
			}
		}
	}

	static class RunHandler6 implements HttpHandler {	
		public void handle(HttpExchange t) throws IOException {
			String ip = getClientIp(t);
			incrementUserStat(ip, "run6"); 
			totalRequestsCounter.incrementAndGet();
			lastSeenMap.put(ip, System.currentTimeMillis());
			
			// אנחנו שולחים את ה-IP ואת שם המשימה "run6"
            updateTaskInDB(ip, "run6");
			
			// הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}
			
			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				
				
				JsonObject req = gson.fromJson(body, JsonObject.class);
					String code = req.get("code").getAsString();
					int[][] image = gson.fromJson(req.get("image"), int[][].class);
					int rowsCount = req.get("rows").getAsInt();
					int colsCount = req.get("cols").getAsInt();

					// 2. חישוב המיקום הראשון שאינו רקע (לוגיקה שלך)
					int rPos = 0, cPos = 0;
					boolean found = false;
					for (int r = 0; r < image.length && !found; r++) {
						for (int c = 0; c < image[r].length; c++) {
							if (image[r][c] != 1) { // מניח ש-1 הוא צבע הרקע
								rPos = r; cPos = c;
								found = true;
								break;
							}
						}
					}
					
				// שימוש בפונקציה המרכזית!
				JsonObject responseJson = executeStudentCodeRepRec(code, image, rPos, cPos, rowsCount, colsCount, "replicateRectangle");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				// שימוש ב-try-with-resources כדי לוודא שהתשובה נשלחת ונסגרת
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}		
			} catch (Exception e) {
				// טיפול בשגיאות (קומפילציה או ריצה)
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				t.getResponseBody().write(b);
			} finally {
				t.close();
			}
		}
	}

	static class RunCreative implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {
			String ip = getClientIp(t);
			incrementUserStat(ip, "run-creative"); 
			totalRequestsCounter.incrementAndGet();
			lastSeenMap.put(ip, System.currentTimeMillis());
			
			// אנחנו שולחים את ה-IP ואת שם המשימה "run-creative"
            updateTaskInDB(ip, "run-creative");
			
			// הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject request = gson.fromJson(body, JsonObject.class);
				
				String code = request.get("code").getAsString();
				int[][] image = gson.fromJson(request.get("image"), int[][].class);

				// שימוש בפונקציה המרכזית!
				JsonObject responseJson = executeStudentCodeImage(code, image, "createImage");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				// שימוש ב-try-with-resources כדי לוודא שהתשובה נשלחת ונסגרת
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} catch (Exception e) {
				// טיפול בשגיאות (קומפילציה או ריצה)
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				t.getResponseBody().write(b);
			} finally {
				t.close();
			}
		}
	}
	
	static class AdminStatsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
        t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");

        List<Map<String, String>> activeStudents = new ArrayList<>();
        int totalGlobalRuns = 0;

        try (Connection conn = getConnection()) {
            // שולף את כל הסטודנטים, הכי פעילים למעלה
            String sql = "SELECT * FROM students ORDER BY last_seen DESC";
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                
                // פורמט תאריך קריא
                java.time.format.DateTimeFormatter formatter = 
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm");

                while (rs.next()) {
                    Map<String, String> s = new HashMap<>();
                    s.put("ip", rs.getString("ip"));
                    s.put("name", rs.getString("name"));
                    
                    int runs = rs.getInt("total_runs");
                    s.put("runCount", String.valueOf(runs));
                    totalGlobalRuns += runs;

                    // הפיכת ה-Timestamp לתאריך קריא
                    long ts = rs.getLong("last_seen");
                    if (ts > 0) {
                        String formattedTime = java.time.Instant.ofEpochMilli(ts)
                            .atZone(java.time.ZoneId.of("Israel")) // או ZoneId.systemDefault()
                            .format(formatter);
                        s.put("lastSeen", formattedTime);
                    } else {
                        s.put("lastSeen", "לעולם לא");
                    }

                    // שליחת ה-JSON של הדירוגים והפירוט כפי שהם
                    s.put("ratingsJson", rs.getString("ratings"));
                    s.put("details", rs.getString("run_counts"));
                    
                    activeStudents.add(s);
                }
            }
        } catch (Exception e) {
            System.err.println("AdminStats Error: " + e.getMessage());
        }

        JsonObject resp = new JsonObject();
        resp.add("activeStudents", new Gson().toJsonTree(activeStudents));
        resp.addProperty("totalRequests", totalGlobalRuns);

        String jsonResponse = resp.toString();
        byte[] b = jsonResponse.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(200, b.length);
        try (OutputStream os = t.getResponseBody()) {
            os.write(b);
        }
        t.close();
    }
}

	/*

	static class AdminStatsHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");

			// בניית רשימת הסטודנטים המעודכנת
			List<Map<String, String>> studentsList = new ArrayList<>();
				
			for (Map.Entry<String, String> entry : studentNames.entrySet()) {
				String ip = entry.getKey();
				Map<String, String> s = new HashMap<>();
				s.put("ip", ip);
				s.put("name", entry.getValue());
				
				// 1. שליפת מפת הפעילויות המפורטת של התלמיד (7 המונים)
				Map<String, Integer> stats = userActivityStats.getOrDefault(ip, new ConcurrentHashMap<>());
				
				// 2. נהפוך את המפה ל-JSON קטן כדי שה-JS יוכל לקרוא את הפירוט
				s.put("details", new com.google.gson.Gson().toJson(stats));
				
				// 3. לטובת התצוגה הראשית בטבלה - נחשב את סך כל ההרצות מכל ה-Handlers
				int totalRuns = stats.values().stream().mapToInt(Integer::intValue).sum();
				s.put("runCount", String.valueOf(totalRuns));
				
				// שליחת חותמת הזמן האחרונה
				long lastSeen = lastSeenMap.getOrDefault(ip, 0L);
				s.put("lastSeen", String.valueOf(lastSeen));
				
				Map<String, Integer> userRatings = feedbackRatings.getOrDefault(ip, new HashMap<>());
				s.put("ratingsJson", new Gson().toJson(userRatings));
				
				s.put("status", "פעיל");
				studentsList.add(s);
			}
			

			JsonObject resp = new JsonObject();
			// התיקון כאן: השם חייב להיות activeStudents
			resp.add("activeStudents", new com.google.gson.Gson().toJsonTree(studentsList));
			resp.addProperty("totalRequests", totalRequestsCounter.get());
			resp.add("levelStats", new JsonObject()); 

			String response = new com.google.gson.Gson().toJson(resp);
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			t.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = t.getResponseBody()) {
				os.write(bytes);
			}
			t.close();
		}
	}
	
	*/
	
	static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            // 1. הגדרות CORS - קריטי לאפשר תקשורת מה-Frontend
            t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            t.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(204, -1);
                return;
            }
            
            // זיהוי ה-IP של הסטודנט
            String ip = getClientIp(t);
            long currentTime = System.currentTimeMillis();
            
            // עדכון מקומי בשרת (בזיכרון)
            lastSeenMap.put(ip, currentTime);

            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                try (InputStream is = t.getRequestBody()) {
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = new Gson().fromJson(body, JsonObject.class);
                    String name = json.get("studentName").getAsString();
                    
                    // --- שמירה/עדכון ב-Database (Postgres) ---
                    try (Connection conn = getConnection()) {
                        // השאילתה מבצעת Insert, ואם ה-IP כבר קיים (Conflict), היא מעדכנת את השם ואת זמן הראייה האחרון
                        String sql = "INSERT INTO students (name, ip, last_seen) VALUES (?, ?, ?) " +
                                     "ON CONFLICT (ip) DO UPDATE SET name = EXCLUDED.name, last_seen = EXCLUDED.last_seen";
                        
                        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                            pstmt.setString(1, name);
                            pstmt.setString(2, ip);
                            pstmt.setLong(3, currentTime);
                            pstmt.executeUpdate();
                        }
                    } catch (Exception dbEx) {
                        // הדפסת שגיאה ללוגים של Railway אבל המשך עבודה כדי שהסטודנט לא ייתקע
                        System.err.println("Database error during registration for IP " + ip + ": " + dbEx.getMessage());
                    }

                    // עדכון מפות מקומיות לשימוש מהיר ב-AdminHandler
                    studentNames.put(ip, name); 
                    if (!onlineUsers.contains(ip)) onlineUsers.add(ip);

                    // שליחת תשובת JSON ללקוח
                    JsonObject responseJson = new JsonObject();
                    responseJson.addProperty("status", "registered");
                    responseJson.addProperty("name", name);
                    
                    String resp = responseJson.toString();
                    t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                    t.sendResponseHeaders(200, resp.getBytes(StandardCharsets.UTF_8).length);
                    try (OutputStream os = t.getResponseBody()) {
                        os.write(resp.getBytes(StandardCharsets.UTF_8));
                    }
                    
                    System.out.println("Student registered: " + name + " | IP: " + ip + " | Time: " + currentTime);
                    
                } catch (Exception e) {
                    System.err.println("Registration failed: " + e.getMessage());
                    t.sendResponseHeaders(400, 0);
                }
            } else {
                t.sendResponseHeaders(405, -1); // Method Not Allowed
            }
            t.close();
        }
    }
	
	/*
	static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            // 1. הגדרות CORS
            t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            t.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(204, -1);
                return;
            }
            
            String ip = getClientIp(t);
            lastSeenMap.put(ip, System.currentTimeMillis());

            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                try (InputStream is = t.getRequestBody()) {
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = new Gson().fromJson(body, JsonObject.class);
                    String name = json.get("studentName").getAsString();
                    
                    // --- שמירה ב-Database (Postgres) ---
                    try (Connection conn = getConnection()) {
                        String sql = "INSERT INTO students (name, ip, last_seen) VALUES (?, ?, ?) " +
                                     "ON CONFLICT (ip) DO UPDATE SET name = EXCLUDED.name, last_seen = EXCLUDED.last_seen";
                        
                        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                            pstmt.setString(1, name);
                            pstmt.setString(2, ip);
                            pstmt.setLong(3, System.currentTimeMillis());
                            pstmt.executeUpdate();
                        }
                    } catch (Exception dbEx) {
                        System.err.println("Database error: " + dbEx.getMessage());
                        // ממשיכים בכל זאת כדי לא לתקוע את התלמיד אם ה-DB זמנית למטה
                    }

                    // עדכון המפות המקומיות (לגיבוי מהיר)
                    studentNames.put(ip, name); 
                    if (!onlineUsers.contains(ip)) onlineUsers.add(ip);

                    // שליחת תשובה ללקוח
                    String resp = "{\"status\":\"registered\",\"name\":\"" + name + "\"}";
                    t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                    t.sendResponseHeaders(200, resp.getBytes(StandardCharsets.UTF_8).length);
                    try (OutputStream os = t.getResponseBody()) {
                        os.write(resp.getBytes(StandardCharsets.UTF_8));
                    }
                    
                    System.out.println("Student registered and saved to DB: " + name + " (IP: " + ip + ")");
                    
                } catch (Exception e) {
                    e.printStackTrace();
                    t.sendResponseHeaders(400, 0);
                }
            } else {
                t.sendResponseHeaders(405, -1);
            }
            t.close();
        }
    }
*/	

	static class ResetAllHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			
			if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
				// איפוס כל המשתנים הסטטיים
				onlineUsers.clear();
				studentNames.clear();
				userActivityStats.clear();
				lastSeenMap.clear();
				totalRequestsCounter.set(0);
				feedbackRatings.clear();

				String resp = "{\"status\":\"reset_success\"}";
				t.sendResponseHeaders(200, resp.length());
				try (OutputStream os = t.getResponseBody()) {
					os.write(resp.getBytes());
				}
			} else {
				t.sendResponseHeaders(405, -1); // Method Not Allowed
			}
			t.close();
		}
	}
	
static class FeedbackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            // הגדרות CORS
            t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            t.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                try (InputStream is = t.getRequestBody()) {
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject json = new Gson().fromJson(body, JsonObject.class);
                    
                    String taskId = json.get("taskId").getAsString(); // למשל "run1"
                    int rating = json.get("rating").getAsInt();
                    String ip = getClientIp(t);
                    
                    // --- עדכון ב-DATABASE (Postgres) ---
                    try (Connection conn = getConnection()) {
                        // מעדכן את הדירוג בתוך אובייקט ה-JSON של הדירוגים
                        String sql = "UPDATE students SET " +
                                     "ratings = jsonb_set(COALESCE(ratings, '{}'), ARRAY[?], ?::text::jsonb), " +
                                     "last_seen = ? " +
                                     "WHERE ip = ?";
                        
                        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                            pstmt.setString(1, taskId);
                            pstmt.setInt(2, rating);
                            pstmt.setLong(3, System.currentTimeMillis());
                            pstmt.setString(4, ip);
                            pstmt.executeUpdate();
                        }
                    } catch (Exception dbEx) {
                        System.err.println("Database Error in FeedbackHandler: " + dbEx.getMessage());
                    }

                    // שמירה במפה המקומית (לגיבוי מהיר)
                    feedbackRatings.computeIfAbsent(ip, k -> new ConcurrentHashMap<>())
                                   .put(taskId, rating);
                    
                    lastSeenMap.put(ip, System.currentTimeMillis());
                    sendTextResponse(t, 200, "{\"status\":\"ok\"}");
                } catch (Exception e) {
                    e.printStackTrace();
                    sendTextResponse(t, 400, "{\"error\":\"invalid data\"}");
                }
            } else {
                t.sendResponseHeaders(405, -1);
            }
            t.close();
        }
    }
/*	
	static class FeedbackHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
				try (InputStream is = t.getRequestBody()) {
					String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
					JsonObject json = new Gson().fromJson(body, JsonObject.class);
					
					String taskId = json.get("taskId").getAsString(); // למשל "run1"
					int rating = json.get("rating").getAsInt();
					String ip = getClientIp(t);
					
					// שמירה בתוך המפה הפנימית של התלמיד
					feedbackRatings.computeIfAbsent(ip, k -> new ConcurrentHashMap<>())
								   .put(taskId, rating);
					
					lastSeenMap.put(ip, System.currentTimeMillis());
					sendTextResponse(t, 200, "{\"status\":\"ok\"}");
				}
			}
			t.close();
		}
	}
*/	
	static class SettingsHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				return;
			}

			if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
				try (InputStream is = t.getRequestBody()) {
					String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
					JsonObject json = new Gson().fromJson(body, JsonObject.class);
					creativeEnabled = json.get("creativeEnabled").getAsBoolean();
				}
				String resp = "{\"status\":\"success\"}";
				t.sendResponseHeaders(200, resp.length());
				try (OutputStream os = t.getResponseBody()) { os.write(resp.getBytes()); }
			} else {
				// GET - מחזיר את המצב הנוכחי
				String resp = "{\"creativeEnabled\":" + creativeEnabled + "}";
				t.sendResponseHeaders(200, resp.length());
				try (OutputStream os = t.getResponseBody()) { os.write(resp.getBytes()); }
			}
			t.close();
		}
	}	
}	

	