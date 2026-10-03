import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Loaded into a running GlassFish through the attach API. Reports, from inside, why a debug message of the per-call
 * code would build a log record: the logging status, and whether the loggers say FINE is loggable. Writes to the file
 * given as the agent argument and changes nothing.
 */
public class LogStatusAgent {
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        try (PrintWriter out = new PrintWriter(args)) {
            LogManager manager = LogManager.getLogManager();
            out.println("manager=" + manager.getClass().getName());
            try {
                Method status = manager.getClass().getMethod("getLoggingStatus");
                out.println("status=" + status.invoke(manager));
            } catch (Exception e) {
                out.println("status=? " + e);
            }
            for (String name : new String[] {"org.glassfish.enterprise.iiop.impl.POAProtocolMgr",
                    "org.glassfish.api.invocation.InvocationManagerImpl", "org.glassfish.exousia.AuthorizationService"}) {
                Logger logger = Logger.getLogger(name);
                out.println(name + " class=" + logger.getClass().getName() + " level=" + logger.getLevel()
                        + " isLoggable(FINE)=" + logger.isLoggable(Level.FINE));
                try {
                    Method m = logger.getClass().getDeclaredMethod("isLoggableLevel", Level.class);
                    m.setAccessible(true);
                    out.println("  isLoggableLevel(FINE)=" + m.invoke(logger, Level.FINE));
                } catch (Exception e) {
                    out.println("  isLoggableLevel=? " + e);
                }
                for (Logger p = logger.getParent(); p != null; p = p.getParent()) {
                    out.println("  parent " + p.getName() + " level=" + p.getLevel());
                }
                System.Logger sys = System.getLogger(name);
                out.println("  System.Logger class=" + sys.getClass().getName() + " isLoggable(DEBUG)="
                        + sys.isLoggable(System.Logger.Level.DEBUG));
            }
            // The loggers the per-call code actually holds: static System.Logger
            // fields, compared with the instance the log manager has now.
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String n = c.getName();
                if (!n.equals("org.glassfish.enterprise.iiop.impl.POAProtocolMgr")
                        && !n.equals("org.glassfish.api.invocation.InvocationManagerImpl")
                        && !n.equals("com.sun.enterprise.iiop.security.SecurityMechanismSelector")
                        && !n.equals("com.sun.enterprise.iiop.security.CSIV2TaggedComponentInfo")) {
                    continue;
                }
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || !System.Logger.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    open(inst, c.getModule(), c.getPackageName());
                    f.setAccessible(true);
                    Object held = f.get(null);
                    out.println(n + "." + f.getName() + " holds " + held.getClass().getName()
                            + " isLoggable(DEBUG)=" + ((System.Logger) held).isLoggable(System.Logger.Level.DEBUG));
                    Logger wrapped = unwrap(inst, held);
                    if (wrapped != null) {
                        Logger current = Logger.getLogger(wrapped.getName());
                        out.println("  wraps " + System.identityHashCode(wrapped) + " level=" + wrapped.getLevel()
                                + " parent=" + (wrapped.getParent() == null ? null : wrapped.getParent().getName())
                                + " handlers=" + wrapped.getHandlers().length + " isLoggable(FINE)=" + wrapped.isLoggable(Level.FINE));
                        out.println("  registered " + System.identityHashCode(current) + " same=" + (current == wrapped));
                    }
                }
            }
        }
    }

    private static void open(Instrumentation inst, Module module, String pkg) {
        if (module.isNamed()) {
            inst.redefineModule(module, Set.of(), Map.of(), Map.of(pkg, Set.of(LogStatusAgent.class.getModule())), Set.of(), Map.of());
        }
    }

    private static Logger unwrap(Instrumentation inst, Object systemLogger) throws Exception {
        for (Class<?> c = systemLogger.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Logger.class.isAssignableFrom(f.getType())) {
                    open(inst, c.getModule(), c.getPackageName());
                    f.setAccessible(true);
                    return (Logger) f.get(systemLogger);
                }
            }
        }
        return null;
    }
}
