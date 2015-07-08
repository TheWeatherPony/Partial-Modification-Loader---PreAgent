package weatherpony.pmlpreinstrumentation;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import com.sun.tools.attach.VirtualMachine;

public class AgentStart{
	public AgentStart(){
		try{
			loadAgentLibrary();
			loadAttachLib();
			attachAgentToJVM();
		}catch(Throwable e){
			if(e instanceof RuntimeException)
				throw (RuntimeException)e;
			throw new RuntimeException(e);
		}
	}
	static void attachAgentToJVM(){
		String JVMPid = getPidFromRuntimeMBean();
		//InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("PMLAgent.jar");
		//CACHE_DIR += JVMPid;
		try {
			URL myLocation = AgentStart.class.getProtectionDomain().getCodeSource().getLocation();
			Path myloc = Paths.get(myLocation.toURI());
			Path agent = myloc.resolveSibling("PMLAgent.jar");
			//File jarFile = File.createTempFile("PMLAgent", ".jar");
			//jarFile.deleteOnExit();
			
			//Files.copy(in, jarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			Thread thread = Thread.currentThread();
			ClassLoader norm = thread.getContextClassLoader();
			thread.setContextClassLoader(ClassLoader.getSystemClassLoader());//set the CL to the system loader
			
			VirtualMachine vm = VirtualMachine.attach(JVMPid);
			vm.loadAgent(agent.toAbsolutePath().toString());
			vm.detach();
			thread.setContextClassLoader(norm);//return the CL to normal *rolls eyes* - moronic bug.
		}catch(Throwable e){
			if(e instanceof RuntimeException){
				throw (RuntimeException)e;
			}
			throw new RuntimeException(e);
		}
	}
	//the rest of this class draws mainly from Xyene's work
	private static final String REV = "1";
    private static final String NATIVE_DIR = "natives/";
    private static final String MINITOOLS_DIR = "attach/";
    private static final String MINITOOLS_JAR = "miniTools.jar";
    private static final String WIN_DIR = "windows/";
    private static final String NIX_DIR = "linux/";
    private static final String MAC_DIR = "mac/";
    private static final String SOLARIS_DIR = "solaris/";
    private static String CACHE_DIR = System.getProperty("java.io.tmpdir") + File.separatorChar + "agentcache.0.0_" + REV + getPidFromRuntimeMBean();;
    
    static String getPidFromRuntimeMBean() {
		String jvm = ManagementFactory.getRuntimeMXBean().getName();
		String pid = jvm.substring(0, jvm.indexOf('@'));
		return pid;
	}
    static void addToLibPath(String path) throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        if (System.getProperty("java.library.path") != null) {
            // If java.library.path is not empty, we will prepend our path
            // Note that path.separator is ; on Windows and : on *nix,
            // so we can't hard code it.
            System.setProperty("java.library.path", path + System.getProperty("path.separator") + System.getProperty("java.library.path"));
        } else {
            System.setProperty("java.library.path", path);
        }

        // Important: java.library.path is cached
        // We will be using reflection to clear the cache
        Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
        fieldSysPath.setAccessible(true);
        fieldSysPath.set(null, null);

    }
    static void loadAttachLib() throws IOException, NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException, NoSuchMethodException, InvocationTargetException{
    	String loc = MINITOOLS_DIR;
    	switch (Platform.getPlatform()){
		case LINUX:
			loc += NIX_DIR;
			break;
		case MAC:
			loc += MAC_DIR;
			break;
		case SOLARIS:
			loc += SOLARIS_DIR;
			break;
		case WINDOWS:
			loc += WIN_DIR;
			break;
		default:
			throw new UnsupportedOperationException("unsupported platform");
    	}
    	loc += MINITOOLS_JAR;
    	InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(loc);
    	File pathDir = new File(CACHE_DIR);
    	File jarPath = new File(pathDir, MINITOOLS_JAR);
		Files.copy(in, jarPath.toPath(), StandardCopyOption.REPLACE_EXISTING);
		
		ClassLoader cur = ClassLoader.getSystemClassLoader();
		if(cur instanceof URLClassLoader){
			URLClassLoader urlcl = (URLClassLoader)cur;
			Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
			addURL.setAccessible(true);
			addURL.invoke(urlcl, jarPath.toURI().toURL());
		}else{
			throw new UnsupportedOperationException("wt...");
		}
    }
    static void loadAgentLibrary() throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException{
    	File destination;
        switch (Platform.getPlatform()) {
            case WINDOWS:
            	destination = unpack(WIN_DIR + "attach.dll");
                break;
            case LINUX:
            	destination = unpack(NIX_DIR + "libattach.so");
                break;
            case MAC:
            	destination = unpack(MAC_DIR + "libattach.dylib");
                break;
            case SOLARIS:
            	destination = unpack(SOLARIS_DIR + "libattach.so");
                break;
            default:
                throw new UnsupportedOperationException("unsupported platform");
        }
        addToLibPath(destination.getAbsoluteFile().getParentFile().getAbsolutePath());
        //System.load(destination.getAbsolutePath());
    }
    
    private static File unpack(String path) {
        try {
            System.out.println(NATIVE_DIR + ((Platform.is64Bit() || Platform.getPlatform() == Platform.MAC) ? "64/" : "32/") + path);
            URL url = Thread.currentThread().getContextClassLoader().getResource(NATIVE_DIR + ((Platform.is64Bit() || Platform.getPlatform() == Platform.MAC) ? "64/" : "32/") + path);

            File pathDir = new File(CACHE_DIR);
            pathDir.mkdirs();
            File libfile = new File(pathDir, path.substring(path.lastIndexOf("/"), path.length()));

            if (!libfile.exists()) {
                libfile.deleteOnExit();
                InputStream in = url.openStream();
                OutputStream out = new BufferedOutputStream(new FileOutputStream(libfile));

                int len;
                byte[] buffer = new byte[8192];
                while ((len = in.read(buffer)) > -1) {
                    out.write(buffer, 0, len);
                }
                out.flush();
                out.close();
                in.close();
            }
            return libfile;
        } catch (IOException x) {
            throw new RuntimeException("could not unpack binaries", x);
        }
    }
    
    public enum Platform {

        LINUX, WINDOWS, MAC, SOLARIS;

        public static Platform getPlatform() {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.indexOf("win") >= 0) {
                return WINDOWS;
            }
            if ((os.indexOf("nix") >= 0) || (os.indexOf("nux") >= 0) || (os.indexOf("aix") > 0)) {
                return LINUX;
            }
            if (os.indexOf("mac") >= 0) {
                return MAC;
            }
            if (os.indexOf("sunos") >= 0)
                return SOLARIS;
            return null;
        }

        public static boolean is64Bit() {
            String osArch = System.getProperty("os.arch");
            return "amd64".equals(osArch) || "x86_64".equals(osArch);
        }
    }
}
