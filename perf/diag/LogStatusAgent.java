import java.io.PrintWriter;
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
    public static void agentmain(String args) throws Exception {
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
        }
    }
}
