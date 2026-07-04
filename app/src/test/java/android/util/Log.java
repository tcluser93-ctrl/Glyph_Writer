package android.util;

/**
 * JVM stub for android.util.Log — compiled only into the unit-test
 * classpath. All methods are no-ops so that production code that calls
 * Log.i/d/e/w compiles and runs without crashing in local JVM tests.
 */
@SuppressWarnings("unused")
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG   = 3;
    public static final int INFO    = 4;
    public static final int WARN    = 5;
    public static final int ERROR   = 6;
    public static final int ASSERT  = 7;

    private Log() {}

    public static int v(String tag, String msg)                          { return 0; }
    public static int v(String tag, String msg, Throwable tr)           { return 0; }
    public static int d(String tag, String msg)                          { return 0; }
    public static int d(String tag, String msg, Throwable tr)           { return 0; }
    public static int i(String tag, String msg)                          { return 0; }
    public static int i(String tag, String msg, Throwable tr)           { return 0; }
    public static int w(String tag, String msg)                          { return 0; }
    public static int w(String tag, String msg, Throwable tr)           { return 0; }
    public static int w(String tag, Throwable tr)                        { return 0; }
    public static int e(String tag, String msg)                          { return 0; }
    public static int e(String tag, String msg, Throwable tr)           { return 0; }
    public static int wtf(String tag, String msg)                        { return 0; }
    public static int wtf(String tag, Throwable tr)                      { return 0; }
    public static int wtf(String tag, String msg, Throwable tr)         { return 0; }
    public static boolean isLoggable(String tag, int level)             { return false; }
    public static String getStackTraceString(Throwable tr)              { return ""; }
    public static int println(int priority, String tag, String msg)     { return 0; }
}
