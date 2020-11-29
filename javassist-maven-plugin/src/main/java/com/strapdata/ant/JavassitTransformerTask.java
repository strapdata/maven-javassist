/*
 * Copyright (c) 2016 Strapdata (contact@strapdata.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.strapdata.ant;

import static java.lang.Thread.currentThread;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import javassist.NotFoundException;
import nl.topicus.plugins.maven.javassist.ClassFileIterator;
import nl.topicus.plugins.maven.javassist.ClassNameDirectoryIterator;
import nl.topicus.plugins.maven.javassist.ClassNameJarIterator;
import nl.topicus.plugins.maven.javassist.ClassTransformer;
import nl.topicus.plugins.maven.javassist.ILogger;
import nl.topicus.plugins.maven.javassist.TransformationException;

public class JavassitTransformerTask  extends Task implements ILogger {

    private static final Class<ClassTransformer> TRANSFORMER_TYPE = ClassTransformer.class;

    private String transformerClass;
    private String directory;
    private Vector filesets = new Vector();

    List<String> processInclusions = new ArrayList<String>();

    public void setTransformer(String transformer) {
        this.transformerClass = transformer;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public void addFileset(FileSet fileset) {
        filesets.add(fileset);
    }

    protected void validate() {
        if (directory==null) throw new BuildException("directory not set");
        if (transformerClass==null) throw new BuildException("transformer not set");
        if (filesets.size()<1) throw new BuildException("fileset not set");
    }


    public void execute() {
        validate();                                                             // 1

        for(Iterator itFSets = filesets.iterator(); itFSets.hasNext(); ) {      // 2
            FileSet fs = (FileSet)itFSets.next();
            DirectoryScanner ds = fs.getDirectoryScanner(getProject());         // 3
            String[] includedFiles = ds.getIncludedFiles();
            for(int i=0; i<includedFiles.length; i++) {
                String filename = includedFiles[i].replace('\\','/');           // 4
                filename = filename.replace('/', '.').replace(".class","");
                processInclusions.add(filename);
            }
        }
        log("Including "+this.processInclusions);

        final ClassLoader originalContextClassLoader = currentThread().getContextClassLoader();
        try {
            final List<String> classpathElements = getCompileClasspathElements();
            loadClassPath(originalContextClassLoader, generateClassPathUrls(classpathElements));
            transform(classpathElements);
        } catch (Exception e) {
            log("Error:"+e, e, 0);
            e.printStackTrace();
        } finally {
            currentThread().setContextClassLoader(originalContextClassLoader);
        }
    }

    public final void transform(final List<String> classPaths) throws Exception {
        int errors = 0;
        if (classPaths.isEmpty())
            return;

        ClassTransformer transformer = instantiateTransformerClass();
        final ClassPool classPool = new ClassPool(ClassPool.getDefault());
        classPool.appendClassPath(directory);
        classPool.appendClassPath(new LoaderClassPath(this.getClass().getClassLoader()));
        classPool.appendClassPath(new LoaderClassPath(Thread.currentThread().getContextClassLoader()));

        final Iterator<String> classPathIterator = classPaths.iterator();
        while (classPathIterator.hasNext()) {
            final String classPath = classPathIterator.next();
            log("Processing " + classPath);
            final ClassFileIterator classNames = createClassNameIterator(classPath);
            while (classNames.hasNext()) {
                final String className = classNames.next();
                try {
                    final CtClass candidateClass = classPool.get(className);
                    if (candidateClass.isFrozen()) {
                        debug("Skipping frozen " + className);
                        continue;
                    }
                    if (!transformer.processClassName(className)) {
                        debug("Exclude " + className);
                        continue;
                    }
                    debug("Transforming " + className);

                    transformer.applyTransformations(classPool, candidateClass);
                    writeFile(candidateClass, directory);
                } catch (final TransformationException e) {
                    errors++;
                    log(classNames.getLastFile()+":"+e.getMessage());
                    continue;
                } catch (final NotFoundException e) {
                    errors++;
                    log(classNames.getLastFile()+":"+String.format(
                            "Class %s could not be resolved due "
                                    + "to dependencies not found on current "
                                    + "classpath.", className)+":"+e);
                    continue;
                } catch (final Exception e) {
                    errors++;
                    log(classNames.getLastFile()+":"+String.format("Class %s could not be transformed.", className)+":"+e);
                    e.printStackTrace();
                    continue;
                }
            }
        }
        if (errors > 0)
            throw new Exception(errors
                    + " errors found during transformation.");
    }

    public void writeFile(CtClass candidateClass, String targetDirectory)
            throws Exception {
        candidateClass.getClassFile().compact();
        candidateClass.rebuildClassFile();

        String classname = candidateClass.getName();
        String filename = targetDirectory + File.separatorChar
                + classname.replace('.', File.separatorChar) + ".class";
        int pos = filename.lastIndexOf(File.separatorChar);
        if (pos > 0) {
            String dir = filename.substring(0, pos);
            if (!dir.equals(".")) {
                File outputDir = new File(dir);
                outputDir.mkdirs();
            }
        }
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(
                        new FileOutputStream(new File(filename))))) {
            candidateClass.toBytecode(out);
        }
    }

    private ClassFileIterator createClassNameIterator(final String classPath) throws IOException {
        if (new File(classPath).isDirectory()) {
            return new ClassNameDirectoryIterator(classPath);
        } else {
            return new ClassNameJarIterator(classPath);
        }
    }

    private List<String> getCompileClasspathElements() {
        log("Scan project.build.outputDirectory="+directory);
        return new ArrayList<>( Arrays.asList(directory));
    }

    protected ClassTransformer instantiateTransformerClass() throws Exception {
        if (transformerClass == null || transformerClass.trim().isEmpty())
            throw new Exception(
                    "Invalid transformer class name passed");

        Class<?> transformerClassInstance;
        try {
            log("Loading class "+transformerClass);
            //transformerClassInstance = Class.forName(transformerClass.trim(),
            //        true, currentThread().getContextClassLoader());
            transformerClassInstance = Class.forName(transformerClass.trim());
        } catch (ClassNotFoundException e) {
            throw e;
        }
        ClassTransformer transformerInstance = null;

        if (TRANSFORMER_TYPE.isAssignableFrom(transformerClassInstance)) {
            try {
                transformerInstance = TRANSFORMER_TYPE
                        .cast(transformerClassInstance.newInstance());
            } catch (InstantiationException | IllegalAccessException e) {
                throw e;
            }
            transformerInstance.setLogger(this);
            transformerInstance.setProcessInclusions(processInclusions);
            //transformerInstance.setProcessExclusions(processExclusions);
            //transformerInstance.setExclusions(exclusions);
        } else {
            throw new Exception(
                    "Transformer class must inherit from "
                            + TRANSFORMER_TYPE.getName());
        }

        return transformerInstance;
    }

    private List<URL> generateClassPathUrls(Iterable<String> classpathElements) {
        final List<URL> classPath = new ArrayList<URL>();
        for (final String runtimeResource : classpathElements) {
            URL url = resolveUrl(runtimeResource);
            if (url != null) {
                classPath.add(url);
            }
        }

        return classPath;
    }

    private void loadClassPath(final ClassLoader contextClassLoader,
            final List<URL> urls) {
        if (urls.size() <= 0)
            return;

        final URLClassLoader pluginClassLoader = URLClassLoader.newInstance(
                urls.toArray(new URL[urls.size()]), contextClassLoader);
        currentThread().setContextClassLoader(pluginClassLoader);
    }

    private URL resolveUrl(final String resource) {
        try {
            return new File(resource).toURI().toURL();
        } catch (final MalformedURLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void addMessage(File file, int line, int pos, String message, Throwable e) {
        // TODO Auto-generated method stub

    }

    @Override
    public void debug(String message) {
        log(message);
    }

    @Override
    public void debug(String message, Throwable throwable) {
        log(message+":"+throwable, throwable, 0);
    }

    @Override
    public void info(String message) {
        log(message);
    }

    @Override
    public void info(String message, Throwable throwable) {
        log(message+":"+throwable, throwable, 0);
    }

    @Override
    public void warn(String message) {
        log(message);
    }

    @Override
    public void warn(String message, Throwable throwable) {
        log(message+":"+throwable, throwable, 0);
    }

    @Override
    public void error(String message) {
        log(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        log(message+":"+throwable, throwable, 0);
    }
}
