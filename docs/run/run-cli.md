# Compile and run with CLI

Requires maven 3.9.6 + and JDK 21

First compile [Twake mail backend](https://github.com/linagora/tmail-backend) as this project depends on some of its snapshots.

In order to compile the application, run:

```bash
mvn clean install
```

Then run it with:

```bash
java -cp twake-calendar-side-service/app/target/twake-calendar-side-service-app-1.0.0-SNAPSHOT.jar com.linagora.calendar.app.TwakeCalendarMain
```