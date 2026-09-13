import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Removes a real declaration from a disposable JAR to challenge the compatibility gate. */
class RemoveApiMember {
    public static void main(String[] arguments) throws Exception {
        Path input = Path.of(arguments[0]);
        Path output = Path.of(arguments[1]);
        String classEntry = arguments[2].replace('.', '/') + ".class";
        String memberName = arguments[3];
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("canary output must differ from the original JAR");
        }
        try (JarFile original = new JarFile(input.toFile())) {
            CtClass type;
            try (var source = original.getInputStream(original.getJarEntry(classEntry))) {
                type = new ClassPool(false).makeClass(source);
            }
            int removed = 0;
            for (CtMethod method : type.getDeclaredMethods()) {
                if (method.getName().equals(memberName)) {
                    type.removeMethod(method);
                    removed++;
                }
            }
            if (memberName.equals("<init>")) {
                for (CtConstructor constructor : type.getDeclaredConstructors()) {
                    type.removeConstructor(constructor);
                    removed++;
                }
            }
            if (removed == 0) {
                throw new IllegalArgumentException("no declaration to remove: " + arguments[2] + "#" + memberName);
            }
            byte[] changed = type.toBytecode();
            type.detach();
            try (JarOutputStream destination = new JarOutputStream(Files.newOutputStream(output))) {
                var entries = original.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    JarEntry copy = new JarEntry(entry.getName());
                    copy.setTime(0L);
                    destination.putNextEntry(copy);
                    if (entry.getName().equals(classEntry)) {
                        destination.write(changed);
                    } else {
                        try (var source = original.getInputStream(entry)) {
                            source.transferTo(destination);
                        }
                    }
                    destination.closeEntry();
                }
            }
            System.out.println("Removed " + removed + " declaration(s): " + arguments[2] + "#" + memberName);
        }
    }
}
