package nl.topicus.plugins.maven.javassist;

import org.sonatype.plexus.build.incremental.BuildContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Collectors;


public class ClassNameDirectoryIterator implements ClassFileIterator {
    private final String classPath;
    private Iterator<File> classFiles = new ArrayList<File>().iterator();
    private File lastFile;

    public ClassNameDirectoryIterator(final String classPath) throws IOException {
        this.classPath = classPath;
        this.classFiles = Files.find( Paths.get(classPath), 999, (p,at) -> p.toFile().getName().endsWith( ".class" ))
                .map( p -> p.toFile() )
                .collect( Collectors.toList() )
                .iterator();
    }

    public ClassNameDirectoryIterator(final String classPath,
            final BuildContext buildContext) throws IOException {
        this.classPath = classPath;
        this.classFiles = Files.find( Paths.get(classPath), 999, (p,at) -> p.toFile().getName().endsWith( ".class" )
                && buildContext.hasDelta(p.toFile()), FileVisitOption.FOLLOW_LINKS )
                .map( p -> p.toFile() )
                .collect( Collectors.toList() )
                .iterator();
    }

    @Override
    public boolean hasNext() {
        return classFiles.hasNext();
    }

    @Override
    public String next() {
        final File classFile = classFiles.next();
        lastFile = classFile;
        try {
            final String qualifiedFileName = classFile.getCanonicalPath()
                    .substring(classPath.length() + 1);
            return getNameWithoutExtension(qualifiedFileName.replace(
                    File.separator, "."));
        } catch (final IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    public File getLastFile() {
        return lastFile;
    }

    @Override
    public void remove() {
        classFiles.remove();
    }

    public static String getNameWithoutExtension(String file) {
        Objects.requireNonNull( file );
        String fileName = (new File(file)).getName();
        int dotIndex = fileName.lastIndexOf(46);
        return dotIndex == -1 ? fileName : fileName.substring(0, dotIndex);
    }
}
