
# שלב 1: בנייה (Build)
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# שלב 2: הרצה (Runtime) - כאן השינוי!
# במקום openjdk:17-jdk-slim, נשתמש בזה:
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# העתקת ה-JAR (שים לב שהשם תואם ל-ArtifactId ב-pom.xml)
COPY --from=build /app/target/AlgorithmicArtServer-1.0-SNAPSHOT.jar app.jar

# הגדרת זיכרון ל-15 תלמידים בתוכנית Hobby
ENTRYPOINT ["java", "-Xmx2g", "-jar", "app.jar"]