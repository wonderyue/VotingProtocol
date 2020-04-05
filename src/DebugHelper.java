public class DebugHelper {
    public enum Level {
        INFO,
        DEBUG
    }
    public static Level logLevel = Level.INFO;
    public static void Log(Level level, String s) {
        if (logLevel.compareTo(level) >= 0)
            System.out.println(s);
    }
}
