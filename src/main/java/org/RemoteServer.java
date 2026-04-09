package org;

import java.sql.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Method;

public class RemoteServer {
	
	private static final List<String> onlineUsers = new CopyOnWriteArrayList<>();
    private static final AtomicInteger totalRequestsCounter = new AtomicInteger(0);
    private static final Map<String, Long> lastSeenMap = new ConcurrentHashMap<>();
    private static final Map<String, String> studentNames = new ConcurrentHashMap<>();
	private static final Map<String, Map<String, Integer>> userActivityStats = new ConcurrentHashMap<>();
	private static final Map<String, Map<String, Integer>> feedbackRatings = new ConcurrentHashMap<>();
	private static boolean creativeEnabled = true;

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
	
	
	
	private static void deleteDirectory(File dir) {
		File[] files = dir.listFiles();
		if (files != null) {
			for (File f : files) f.delete();
		}
		dir.delete();
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
                   "    public static void run(int[][] image) {\n" + // שינוי ל-void
                   "        " + wrapperMethodName + "(image);\n" +   // הסרת ה-return
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
																		
									// 1. הרצת קוד התלמיד (מתעלמים מהערך החוזר של ה-invoke)
									method.invoke(null, (Object) image);

									// 2. השמת המערך המקורי לתוך ה-resultHolder (הוא כבר מכיל את השינויים של התלמיד)
									resultHolder[0] = image;
									
									System.out.flush();
								} finally {
									System.setOut(originalOut);
								}
								logHolder[0] = baos.toString(StandardCharsets.UTF_8);
							}
						//} catch (Exception e) {
						//	throw new RuntimeException(e);
						//}
						} catch (Exception e) {
							throw new RuntimeException("Database connection failed: " + e.getMessage(), e);
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
							throw new RuntimeException("Database connection failed: " + e.getMessage(), e);
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
				   "    public static void run(int[][] image, int r, int c, int count) {\n" +
                   "        " + wrapperMethodName + "(image, r, c, count);\n" +   // הסרת ה-return
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
																		
									// 1. הרצת קוד התלמיד (מתעלמים מהערך החוזר של ה-invoke)
									method.invoke(null, (Object) image, r, c, count);

									// 2. השמת המערך המקורי לתוך ה-resultHolder (הוא כבר מכיל את השינויים של התלמיד)
									resultHolder[0] = image;
									
									System.out.flush();
								} finally {
									System.setOut(originalOut);
								}
								logHolder[0] = baos.toString(StandardCharsets.UTF_8);
							}
						} catch (Exception e) {
							throw new RuntimeException("Database connection failed: " + e.getMessage(), e);
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
/*
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
				   "    public static void run(int[][] image, int r, int c, int rCount, int cCount) {\n" +
                   "        " + wrapperMethodName + "(image, r, c, rCount, cCount);\n" +   // הסרת ה-return
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
									
									// 1. הרצת קוד התלמיד (מתעלמים מהערך החוזר של ה-invoke)
									method.invoke(null, (Object) image, r, c, rCount, cCount);

									// 2. השמת המערך המקורי לתוך ה-resultHolder (הוא כבר מכיל את השינויים של התלמיד)
									resultHolder[0] = image;
									
									System.out.flush();
								} finally {
									System.setOut(originalOut);
								}
								logHolder[0] = baos.toString(StandardCharsets.UTF_8);
							}
						} catch (Exception e) {
							//throw new RuntimeException("Amir Database connection failed", e.toString());
							
							// throw new RuntimeException(e.getMessage() != null ? e.getMessage() : e.toString());
							
							String message = (e.getMessage() != null) ? e.getMessage() : e.toString();

							throw new RuntimeException("Amir DB error: " + message, e);
							
						}
					});

					// future.get(5, java.util.concurrent.TimeUnit.SECONDS);
					
					
					try {
						future.get(5, java.util.concurrent.TimeUnit.SECONDS);

					} catch (java.util.concurrent.ExecutionException e) {
						Throwable cause = e.getCause();

						String message = (cause.getMessage() != null)
								? cause.getMessage()
								: cause.toString();

						throw new Exception("Student code error: " + message, cause);					
					
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
*/

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
               "    public static void run(int[][] image, int r, int c, int rCount, int cCount) {\n" +
               "        " + wrapperMethodName + "(image, r, c, rCount, cCount);\n" +
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
                    PrintStream originalOut = System.out;
                    try {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (PrintStream newOut = new PrintStream(baos)) {
                            System.setOut(newOut);                                    
                            
                            // הרצת קוד התלמיד
                            method.invoke(null, (Object) image, r, c, rCount, cCount);

                            resultHolder[0] = image;
                            System.out.flush();
                        }
                        logHolder[0] = baos.toString(StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        // קילוף השגיאה כדי להציג OutOfBounds במקום InvocationTarget
                        Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException) ? e.getCause() : e;
                        
                        // שימוש ב-RuntimeException פותר את שגיאת ה-Build (שורה 214)
                        throw new RuntimeException(cause != null ? cause.toString() : e.toString());
                    } finally {
                        System.setOut(originalOut);
                    }
                });

                future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                
            } catch (ExecutionException e) {
                // כאן אנחנו מחלצים את השגיאה שזרקנו מה-RuntimeException למעלה
                Throwable realError = (e.getCause() != null) ? e.getCause() : e;
                throw new Exception(realError.getMessage());
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
	
	private static void updateTaskInDB(String studentId, String taskName, String currentIp) {
		try (Connection conn = getConnection()) {
			// השאילתה מעדכנת את סך ההרצות, את ה-IP האחרון ואת המונה בתוך ה-JSON
			String sql = "UPDATE students SET " +
						 "total_runs = total_runs + 1, " +
						 "last_seen = ?, " +
						 "ip = ?, " + 
						 "run_counts = jsonb_set(COALESCE(run_counts, '{}'), ARRAY[?], " +
						 "(COALESCE(run_counts->>?, '0')::int + 1)::text::jsonb) " +
						 "WHERE student_id = ?"; 
			try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
				long now = System.currentTimeMillis();
				pstmt.setLong(1, now);
				pstmt.setString(2, currentIp);
				pstmt.setString(3, taskName);
				pstmt.setString(4, taskName);
				pstmt.setString(5, studentId);
				int rowsAffected = pstmt.executeUpdate();
				System.out.println("DB Update result: " + rowsAffected + " rows for " + studentId);
			}
		} catch (Exception e) {
			System.err.println("Error updating DB for student " + studentId + ": " + e.getMessage());
		}
	}
	
	private static void checkAndCreateStudent(String studentId, String name, String ip) {
		// השאילתה בודקת אם ה-student_id קיים. אם לא - יוצרת. אם כן - מעדכנת שם ו-IP.
		String sql = "INSERT INTO students (student_id, name, ip, last_seen, total_runs, run_counts) " +
					 "VALUES (?, ?, ?, ?, 0, '{}'::jsonb) " +
					 "ON CONFLICT (student_id) DO UPDATE SET name = EXCLUDED.name, ip = EXCLUDED.ip, last_seen = EXCLUDED.last_seen";
		
		try (Connection conn = getConnection(); 
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, studentId);
			pstmt.setString(2, name);
			pstmt.setString(3, ip);
			pstmt.setLong(4, System.currentTimeMillis());
			pstmt.executeUpdate();
		} catch (Exception e) {
			System.err.println("Error ensuring student exists: " + e.getMessage());
		}
	}

	static class RunHandler1 implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {     
			// 1. הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();
			try (InputStream is = t.getRequestBody()) {
				// 2. קריאת ה-JSON מהדפדפן
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject request = gson.fromJson(body, JsonObject.class);
				
				String currentIp = getClientIp(t);
				
				// 3. חילוץ המזהה הייחודי והשם
				String studentId = request.has("studentId") ? request.get("studentId").getAsString() : currentIp;
				String studentName = request.has("name") ? request.get("name").getAsString() : "אורח";
				
				// 4. עדכון הסטטיסטיקות בזיכרון (Maps)
				incrementUserStat(studentId, "run1"); 
				totalRequestsCounter.incrementAndGet();
				lastSeenMap.put(studentId, System.currentTimeMillis());
				studentNames.put(studentId, studentName); 

				// 5. עדכון ב-Database
				// קודם מוודאים שהתלמיד קיים (UPSERT), ואז מעדכנים את המשימה
				checkAndCreateStudent(studentId, studentName, currentIp);
				updateTaskInDB(studentId, "run1", currentIp); // תיקון: בלי המילה String בקריאה

				// 6. הרצת הקוד כרגיל
				String code = request.get("code").getAsString();
				int[][] image = gson.fromJson(request.get("image"), int[][].class);

				JsonObject responseJson = executeStudentCodeImage(code, image, "addFrame");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} catch (Exception e) {
				e.printStackTrace();
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} finally {
				t.close();
			}
		}
	}
	
	static class RunHandler2 implements HttpHandler {    
		public void handle(HttpExchange t) throws IOException {
			// 1. הגדרות Header רגילות (CORS) - הוספת Headers מלאים לתמיכה בדפדפנים
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				// 2. קריאת ה-JSON מהדפדפן (חובה לעשות זאת לפני שמשתמשים בנתונים)
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject request = gson.fromJson(body, JsonObject.class);
				
				String currentIp = getClientIp(t);
				
				// 3. חילוץ המזהה הייחודי (LocalStorage) והשם
				String studentId = request.has("studentId") ? request.get("studentId").getAsString() : currentIp;
				String studentName = request.has("name") ? request.get("name").getAsString() : "אורח";

				// 4. עדכון הסטטיסטיקות בזיכרון (Maps) לפי studentId
				incrementUserStat(studentId, "run2"); 
				totalRequestsCounter.incrementAndGet();
				lastSeenMap.put(studentId, System.currentTimeMillis());
				studentNames.put(studentId, studentName); // עדכון השם במפה בזיכרון

				// 5. עדכון ב-Database
				// מוודאים שהתלמיד קיים בטבלה עם ה-ID הזה, ואז מעדכנים את המשימה
				checkAndCreateStudent(studentId, studentName, currentIp);
				updateTaskInDB(studentId, "run2", currentIp);

				// 6. הרצת הקוד הלוגי
				String code = request.get("code").getAsString();
				int[][] image = gson.fromJson(request.get("image"), int[][].class);

				// שימוש בפונקציה המרכזית (אלגוריתם האלכסון)
				JsonObject responseJson = executeStudentCodeImage(code, image, "createDiagonal");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				
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
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} finally {
				t.close();
			}
		}
	}

	static class RunHandler3 implements HttpHandler {    
		public void handle(HttpExchange t) throws IOException {
			// 1. הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				// 2. קריאת ה-JSON מהדפדפן
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject request = gson.fromJson(body, JsonObject.class);
				
				String currentIp = getClientIp(t);
				
				// 3. חילוץ המזהה הייחודי (LocalStorage) והשם
				String studentId = request.has("studentId") ? request.get("studentId").getAsString() : currentIp;
				String studentName = request.has("name") ? request.get("name").getAsString() : "אורח";

				// 4. עדכון הסטטיסטיקות בזיכרון (Maps) לפי ה-studentId הקבוע
				incrementUserStat(studentId, "run3"); 
				totalRequestsCounter.incrementAndGet();
				lastSeenMap.put(studentId, System.currentTimeMillis());
				studentNames.put(studentId, studentName);

				// 5. עדכון ב-Database
				// מוודאים שהתלמיד קיים (לפי studentId) ואז מעדכנים את משימה 3
				checkAndCreateStudent(studentId, studentName, currentIp);
				updateTaskInDB(studentId, "run3", currentIp);

				// 6. הרצת הקוד הלוגי
				String code = request.get("code").getAsString();
				int[][] image = gson.fromJson(request.get("image"), int[][].class);

				// כאן מתבצעת הפונקציה הספציפית לשלב 3: findMaxDiagonal
				JsonObject responseJson = executeStudentCodeInt(code, image, "findMaxDiagonal");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} catch (Exception e) {
				// טיפול בשגיאות
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} finally {
				t.close();
			}
		}
	}


	static class RunHandler4 implements HttpHandler {    
		public void handle(HttpExchange t) throws IOException {
			// 1. הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				// 2. קריאת ה-JSON מהדפדפן (חובה לבצע לפני השימוש ב-studentId)
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject request = gson.fromJson(body, JsonObject.class);
				
				String currentIp = getClientIp(t);
				
				// 3. חילוץ המזהה הייחודי (LocalStorage) והשם
				String studentId = request.has("studentId") ? request.get("studentId").getAsString() : currentIp;
				String studentName = request.has("name") ? request.get("name").getAsString() : "אורח";

				// 4. עדכון הסטטיסטיקות בזיכרון (Maps) לפי ה-studentId הקבוע
				incrementUserStat(studentId, "run4"); 
				totalRequestsCounter.incrementAndGet();
				lastSeenMap.put(studentId, System.currentTimeMillis());
				studentNames.put(studentId, studentName);

				// 5. עדכון ב-Database
				// מוודאים שהתלמיד קיים (לפי studentId) ואז מעדכנים את משימה 4
				checkAndCreateStudent(studentId, studentName, currentIp);
				updateTaskInDB(studentId, "run4", currentIp);

				// 6. הרצת הקוד הלוגי
				String code = request.get("code").getAsString();
				int[][] image = gson.fromJson(request.get("image"), int[][].class);

				// הרצת השלב הספציפי: findMinSecondaryDiagonal
				JsonObject responseJson = executeStudentCodeInt(code, image, "findMinSecondaryDiagonal");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} catch (Exception e) {
				// טיפול בשגיאות
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} finally {
				t.close();
			}
		}
	}
	
	static class RunHandler5 implements HttpHandler {    
		public void handle(HttpExchange t) throws IOException {
			// 1. הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				// 2. קריאת ה-JSON מהדפדפן בתחילת התהליך
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject req = gson.fromJson(body, JsonObject.class);
				
				String currentIp = getClientIp(t);
				
				// 3. חילוץ המזהה הייחודי (LocalStorage) והשם
				String studentId = req.has("studentId") ? req.get("studentId").getAsString() : currentIp;
				String studentName = req.has("name") ? req.get("name").getAsString() : "אורח";

				// 4. עדכון הסטטיסטיקות בזיכרון (Maps) לפי ה-studentId הקבוע
				incrementUserStat(studentId, "run5"); 
				totalRequestsCounter.incrementAndGet();
				lastSeenMap.put(studentId, System.currentTimeMillis());
				studentNames.put(studentId, studentName);

				// 5. עדכון ב-Database
				// מוודאים שהתלמיד קיים בטבלה, ואז מעדכנים את משימה 5
				checkAndCreateStudent(studentId, studentName, currentIp);
				updateTaskInDB(studentId, "run5", currentIp);

				// 6. חילוץ נתוני הקוד והתמונה
				String code = req.get("code").getAsString();
				int[][] image = gson.fromJson(req.get("image"), int[][].class);
				int countToReplicate = req.has("count") ? req.get("count").getAsInt() : 1;

				// חישוב המיקום הראשון שאינו רקע (לוגיקה שלך)
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
				
				// 7. הרצת הקוד הלוגי - replicatePixel
				JsonObject responseJson = executeStudentCodeRepPixel(code, image, rPos, cPos, countToReplicate, "replicatePixel");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}           
			} catch (Exception e) {
				// טיפול בשגיאות
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} finally {
				t.close();
			}
		}
	}

	static class RunHandler6 implements HttpHandler {    
		public void handle(HttpExchange t) throws IOException {
			// 1. הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}
			
			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				// 2. קריאת ה-JSON מהדפדפן כבר עכשיו
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject req = gson.fromJson(body, JsonObject.class);
				
				String currentIp = getClientIp(t);
				
				// 3. חילוץ המזהה הייחודי (LocalStorage) והשם
				String studentId = req.has("studentId") ? req.get("studentId").getAsString() : currentIp;
				String studentName = req.has("name") ? req.get("name").getAsString() : "אורח";

				// 4. עדכון הסטטיסטיקות לפי המזהה הקבוע (studentId) במקום ה-IP
				incrementUserStat(studentId, "run6"); 
				totalRequestsCounter.incrementAndGet();
				lastSeenMap.put(studentId, System.currentTimeMillis());
				studentNames.put(studentId, studentName);

				// 5. עדכון ב-Database
				// מוודאים שהתלמיד קיים בטבלה לפי studentId, ואז מעדכנים את משימה 6
				checkAndCreateStudent(studentId, studentName, currentIp);
				updateTaskInDB(studentId, "run6", currentIp);

				// 6. חילוץ נתוני הקוד והפרמטרים
				String code = req.get("code").getAsString();
				int[][] image = gson.fromJson(req.get("image"), int[][].class);
				int rowsCount = req.get("rows").getAsInt();
				int colsCount = req.get("cols").getAsInt();

				// חישוב המיקום הראשון שאינו רקע (לוגיקה שלך)
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
					
				// 7. הרצת הקוד הלוגי - replicateRectangle
				JsonObject responseJson = executeStudentCodeRepRec(code, image, rPos, cPos, rowsCount, colsCount, "replicateRectangle");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}       
			} catch (Exception e) {
				// טיפול בשגיאות
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} finally {
				t.close();
			}
		}
	}

	static class RunCreative implements HttpHandler {
		public void handle(HttpExchange t) throws IOException {
			// 1. הגדרות Header רגילות (CORS)
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			Gson gson = new Gson();

			try (InputStream is = t.getRequestBody()) {
				// 2. קריאת ה-JSON מהדפדפן
				String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject request = gson.fromJson(body, JsonObject.class);
				
				String currentIp = getClientIp(t);
				
				// 3. חילוץ המזהה הייחודי (LocalStorage) והשם
				String studentId = request.has("studentId") ? request.get("studentId").getAsString() : currentIp;
				String studentName = request.has("name") ? request.get("name").getAsString() : "אורח";

				// 4. עדכון הסטטיסטיקות בזיכרון (Maps) לפי ה-studentId הקבוע
				incrementUserStat(studentId, "run-creative"); 
				totalRequestsCounter.incrementAndGet();
				lastSeenMap.put(studentId, System.currentTimeMillis());
				studentNames.put(studentId, studentName);

				// 5. עדכון ב-Database
				// מוודאים שהתלמיד קיים (לפי studentId) ואז מעדכנים את משימת היצירה
				checkAndCreateStudent(studentId, studentName, currentIp);
				updateTaskInDB(studentId, "run-creative", currentIp);

				// 6. הרצת הקוד הלוגי
				String code = request.get("code").getAsString();
				int[][] image = gson.fromJson(request.get("image"), int[][].class);

				// שימוש בפונקציה המרכזית ליצירת תמונה חופשית
				JsonObject responseJson = executeStudentCodeImage(code, image, "createImage");
				
				byte[] b = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(200, b.length);
				
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
			} catch (Exception e) {
				// טיפול בשגיאות
				JsonObject errorJson = new JsonObject();
				errorJson.addProperty("error", e.getMessage());
				byte[] b = gson.toJson(errorJson).getBytes(StandardCharsets.UTF_8);
				t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
				t.sendResponseHeaders(400, b.length);
				try (OutputStream os = t.getResponseBody()) {
					os.write(b);
				}
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
				// שליפת כל הנתונים, מסודרים לפי מי שהיה פעיל לאחרונה
				String sql = "SELECT * FROM students ORDER BY last_seen DESC";
				try (PreparedStatement pstmt = conn.prepareStatement(sql);
					 ResultSet rs = pstmt.executeQuery()) {
					
					DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("Israel"));

					while (rs.next()) {
						Map<String, String> s = new HashMap<>();
						
						// המזהים הבסיסיים
						s.put("studentId", rs.getString("student_id")); // הוספנו את המזהה הייחודי
						s.put("name", rs.getString("name"));
						s.put("ip", rs.getString("ip")); // יציג את ה-IP האחרון ממנו התלמיד התחבר
						
						int runs = rs.getInt("total_runs");
						s.put("runCount", String.valueOf(runs));
						totalGlobalRuns += runs;

						long ts = rs.getLong("last_seen");
						s.put("lastSeen", ts > 0 ? formatter.format(Instant.ofEpochMilli(ts)) : "-");

						// השורה שחסרה לך:
						s.put("lastSeenMillis", String.valueOf(ts));

						// שליחת ה-JSON-ים של הדירוגים והרצות ל-Frontend של האדמין
						// שים לב: וודא ששמות העמודות ב-getString תואמים בדיוק למה שיש ב-DB שלך
						s.put("ratingsJson", rs.getString("ratings")); 
						s.put("runCountsJson", rs.getString("run_counts"));
						
						activeStudents.add(s);
					}
				}
			} catch (Exception e) { 
				System.err.println("Error in AdminStatsHandler: " + e.getMessage());
				e.printStackTrace(); 
			}

			JsonObject resp = new JsonObject();
			resp.add("activeStudents", new Gson().toJsonTree(activeStudents));
			// totalRequests מציג את סך כל ההרצות של כל הכיתה יחד
			resp.addProperty("totalRequests", totalGlobalRuns);

			byte[] b = resp.toString().getBytes(StandardCharsets.UTF_8);
			t.sendResponseHeaders(200, b.length);
			try (OutputStream os = t.getResponseBody()) { 
				os.write(b); 
			}
			t.close();
		}
	}
	

	static class RegisterHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			// 1. הגדרות CORS
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
			t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
				try (InputStream is = t.getRequestBody()) {
					String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
					JsonObject json = new Gson().fromJson(body, JsonObject.class);
					
					// חילוץ הנתונים החדשים מה-Frontend
					String name = json.get("studentName").getAsString();
					// אם הלקוח עדיין לא שלח studentId (גרסה ישנה), נשתמש ב-IP כגיבוי
					//String studentId = json.has("studentId") ? json.get("studentId").getAsString() : getClientIp(t);
					String studentId = json.get("studentId").getAsString();
					
					String ip = getClientIp(t);
					long currentTime = System.currentTimeMillis();

					// 2. עדכון ב-Database (Postgres)
					try (Connection conn = getConnection()) {
						// השינוי הקריטי: ON CONFLICT בודק עכשיו את ה-student_id ולא את ה-IP
						String sql = "INSERT INTO students (student_id, name, ip, last_seen) VALUES (?, ?, ?, ?) " +
									 "ON CONFLICT (student_id) DO UPDATE SET name = EXCLUDED.name, ip = EXCLUDED.ip, last_seen = EXCLUDED.last_seen";
						
						try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
							pstmt.setString(1, studentId);
							pstmt.setString(2, name);
							pstmt.setString(3, ip);
							pstmt.setLong(4, currentTime);
							int rowsAffected = pstmt.executeUpdate();
							System.out.println("DB Update result: " + rowsAffected + " rows for " + studentId);
						}
					} catch (Exception dbEx) {
						System.err.println("Database error during registration for ID " + studentId + ": " + dbEx.getMessage());
					}
					
					// 3. עדכון מפות מקומיות (לשימוש בזיכרון של השרת)
					// משתמשים ב-studentId כמפתח במקום ב-IP
					studentNames.put(studentId, name); 
					lastSeenMap.put(studentId, currentTime);
					if (!onlineUsers.contains(studentId)) onlineUsers.add(studentId);

					// 4. שליחת תשובה
					JsonObject responseJson = new JsonObject();
					responseJson.addProperty("status", "registered");
					responseJson.addProperty("studentId", studentId);
					
					String resp = responseJson.toString();
					t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
					t.sendResponseHeaders(200, resp.getBytes(StandardCharsets.UTF_8).length);
					try (OutputStream os = t.getResponseBody()) {
						os.write(resp.getBytes(StandardCharsets.UTF_8));
					}
					
					System.out.println("Student registered: " + name + " | ID: " + studentId + " | IP: " + ip);
					
				} catch (Exception e) {
					System.err.println("Registration failed: " + e.getMessage());
					t.sendResponseHeaders(400, 0);
				}
			} else {
				t.sendResponseHeaders(405, -1);
			}
			t.close();
		}
	}

	static class ResetAllHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			// הוספת CORS כדי שהדפדפן לא יחסום את הבקשה
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			
			if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
				try (Connection conn = getConnection();
					 Statement stmt = conn.createStatement()) {
					
					// 1. מחיקת הנתונים מה-Database
					stmt.executeUpdate("DELETE FROM students");
					
					// 2. איפוס המפות בזיכרון השרת
					feedbackRatings.clear();
					lastSeenMap.clear();
					studentNames.clear();
					onlineUsers.clear();
					totalRequestsCounter.set(0); // איפוס מונה ההרצות הכללי

					String resp = "{\"status\":\"success\"}";
					t.sendResponseHeaders(200, resp.length());
					try (OutputStream os = t.getResponseBody()) { 
						os.write(resp.getBytes(StandardCharsets.UTF_8)); 
					}
				} catch (Exception e) {
					e.printStackTrace();
					sendTextResponse(t, 500, "{\"error\":\"" + e.getMessage() + "\"}");
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
			// 1. הגדרות CORS
			t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			t.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
			t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

			if ("OPTIONS".equalsIgnoreCase(t.getRequestMethod())) {
				t.sendResponseHeaders(204, -1);
				t.close();
				return;
			}

			if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
				try (InputStream is = t.getRequestBody()) {
					String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
					JsonObject json = new Gson().fromJson(body, JsonObject.class);
					
					// חילוץ הנתונים מה-JSON
					String taskId = json.get("taskId").getAsString(); // למשל "run1"
					int rating = json.get("rating").getAsInt();
					
					// המזהה הקריטי: studentId מה-LocalStorage
					String currentIp = getClientIp(t);
					String studentId = json.has("studentId") ? json.get("studentId").getAsString() : currentIp;
					
					// --- עדכון ב-DATABASE (Postgres) ---
					try (Connection conn = getConnection()) {
						// עדכון לפי student_id במקום לפי IP
						String sql = "UPDATE students SET " +
									 "ratings = jsonb_set(COALESCE(ratings, '{}'), ARRAY[?], ?::text::jsonb), " +
									 "last_seen = ?, " +
									 "ip = ? " + // נעדכן גם את ה-IP האחרון ליופי ב-Admin
									 "WHERE student_id = ?";
						
						try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
							pstmt.setString(1, taskId);
							pstmt.setInt(2, rating);
							pstmt.setLong(3, System.currentTimeMillis());
							pstmt.setString(4, currentIp);
							pstmt.setString(5, studentId);
							pstmt.executeUpdate();
						}
					} catch (Exception dbEx) {
						System.err.println("Database Error in FeedbackHandler: " + dbEx.getMessage());
					}

					// עדכון במפות המקומיות בזיכרון השרת לפי studentId
					feedbackRatings.computeIfAbsent(studentId, k -> new ConcurrentHashMap<>())
								   .put(taskId, rating);
					
					lastSeenMap.put(studentId, System.currentTimeMillis());
					
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

	