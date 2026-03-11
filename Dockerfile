# שלב 1: בנייה
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# שלב 2: הרצה
FROM openjdk:17-jdk-slim
WORKDIR /app
# העתקת ה-JAR שכולל בתוכו גם את ה-index.html (בזכות ה-resources)
COPY --from=build /app/target/AlgorithmicArtServer-1.0-SNAPSHOT.jar app.jar

# חשוב: התקנת כלי קומפילציה בסיסיים אם ה-JDK Slim לא כולל את הכל
# למרות שבדר"כ ב-JDK יש את ה-javac הנדרש לקוד שלך

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]