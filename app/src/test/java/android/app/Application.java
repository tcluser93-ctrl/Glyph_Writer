package android.app;

import android.content.Context;
import android.content.ContextWrapper;

/**
 * JVM stub for android.app.Application — compiled only into the
 * unit-test classpath.  Extends ContextWrapper so that AndroidViewModel
 * (which takes Application as a constructor arg) compiles without
 * requiring the full Android framework on the JVM classpath.
 */
@SuppressWarnings("unused")
public class Application extends ContextWrapper {
    public Application() { super(null); }
    public Context getApplicationContext() { return this; }
}
