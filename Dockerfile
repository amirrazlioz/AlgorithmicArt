
# שלב הבנייה
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
# פקודת בנייה שמנקה הכל ובונת מחדש
RUN mvn clean package -DskipTests

# שלב ההרצה
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# העתקה עם Wildcard כדי לוודא שאנחנו תופסים את ה-JAR הנכון
COPY --from=build /app/target/AlgorithmicArtServer-*.jar app.jar

# הגבלת זיכרון ל-512MB כדי שלא יקרוס ב-Railway
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]