import com.sun.tools.attach.VirtualMachine;

/** Loads an agent jar into a running JVM: Attach <pid> <jar> <agent argument>. */
public class Attach {
    public static void main(String[] a) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(a[0]);
        try {
            vm.loadAgent(a[1], a[2]);
        } finally {
            vm.detach();
        }
    }
}
