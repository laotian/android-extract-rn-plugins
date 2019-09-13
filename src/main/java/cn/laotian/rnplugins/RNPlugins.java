package cn.laotian.rnplugins;


import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RNPlugins {

    private static class Plugin{
        private static class Method{
            String name;
            List<String> paramNames=new ArrayList<>();
            List<String> paramsTypes = new ArrayList<>();
        }
        String superName;
        String name;
        String description;
        List<Method> methods = new ArrayList<>();
        boolean hasConstants =  false;
    }


    public static void extract1(String sourceDir, String sourceFileDescRegularExpress, String classDir, List<String> parentClassNames, String manifestSaveTo, String pluginFileSaveTo) throws IOException {
        if(parentClassNames==null || parentClassNames.size()==0){
            parentClassNames = Arrays.asList(uglyClassName("com.facebook.react.bridge.ReactContextBaseJavaModule"),uglyClassName("com.facebook.react.bridge.BaseJavaModule"));
        }
        if(isEmpty(manifestSaveTo)){
            throw new IllegalStateException("需要提供插件清单列表文件存储位置");
        }
        if(isEmpty(pluginFileSaveTo)){
            throw new IllegalStateException("需要提供插件信息文件存储位置");
        }
        if(isEmpty(sourceFileDescRegularExpress)){
            sourceFileDescRegularExpress = "@desc\\b+(.*)";
        }
        List<String> classFileList = traverseFile(new File(classDir),new File(classDir));
//        List<String> sourceFileList = traverseFile(new File(sourceDir),new File(sourceDir));
//        List<File> sourceFileList = new ArrayList<>();
//        for(String sourceDir : sourceDirList){
//
//        }
        List<Plugin> plugins = new ArrayList<>();
        for(String classFileRelativePath :classFileList){
            String classFile = new File(classDir,classFileRelativePath).getAbsolutePath();
            String sourceFile = new File(sourceDir,classFileRelativePath).getAbsolutePath().replace(".class",".java");
           Plugin plugin =  extract(classFile ,parentClassNames,sourceFile,sourceFileDescRegularExpress);
           if(plugin!=null){
               plugins.add(plugin);
           }
        }

        List<String> pluginNames = new ArrayList<>();
        for(Plugin plugin:plugins){
            pluginNames.add(plugin.name);
        }
        save(pluginNames,manifestSaveTo);

        List<String> importList =new ArrayList<>();
        List<String> pluginContents = new ArrayList<>();
        pluginContents.add("import {");
        for(Plugin plugin:plugins){
            pluginContents.add("  "+plugin.name+",");
        }
        pluginContents.add("} from 'NativeModules'");
        pluginContents.add("");
        for(Plugin plugin:plugins){
            pluginContents.add("///////////////////////////////////////////");
            if(!isEmpty(plugin.description)){
                pluginContents.add("/**");
                pluginContents.add(" * @desc " + plugin.description);
                pluginContents.add(" */");
            }
            String proxyName = plugin.name+"Proxy";
            pluginContents.add("const " + proxyName + " = {");

            for(Plugin.Method method:plugin.methods){
                pluginContents.add("/**");
                for(int i=0;i<method.paramNames.size();i++){
                    String paramName = method.paramNames.get(i);
                    String paramType = method.paramsTypes.get(i);
                    pluginContents.add(" * @param " + paramName +"{" + paramType+ "}" );
                }
                pluginContents.add(" */");
                pluginContents.add(method.name+"() {");
                pluginContents.add(plugin.name+"."+method.name+"();");
                pluginContents.add("}");
                pluginContents.add("");
            }

            if(plugin.hasConstants){
                pluginContents.add("// -- 此插件定义了常量，请在此处添加");
            }
            pluginContents.add("}");
            pluginContents.add(String.format("Object.assign(%s,%s);",proxyName,plugin.name));
            pluginContents.add("");
            pluginContents.add("");

        }

        pluginContents.add("export {");
        for(Plugin plugin:plugins){
            pluginContents.add("  " + plugin.name+"Proxy  as "+plugin.name);
        }
        pluginContents.add("}");
        pluginContents.add("");

        save(pluginContents,pluginFileSaveTo);


        System.out.println("plugins count=="+plugins.size());

    }


    private static void save(List<String> content,String filePath) throws IOException{
        BufferedWriter writer =new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath,false),"utf8"));
        for(String data:content) {
            writer.write(data);
            writer.newLine();
        }
        writer.close();
    }

    /**
     * 遍历访问文件
     *
     * @param file 当前文件（文件夹）
     * @return 返回文件list
     */
    static List<String> traverseFile(File rootPath, File file) {
        List<String> tFiles = new ArrayList<>();
        if (file.exists()) {
            File[] files = file.listFiles();
            for (File subFile : files) {
                if(subFile.isDirectory()){
                    List<String> subFiles = traverseFile(rootPath,subFile);
                    tFiles.addAll(subFiles);
                }else if (subFile.getName().endsWith(".java") || subFile.getName().endsWith(".class")){
                    tFiles.add(subFile.getAbsolutePath().substring(rootPath.getAbsolutePath().length()));
                }
            }
        }
        return tFiles;
    }





    private static boolean isEmpty(String line){
        return line==null || line.length()==0;
    }

    private static List<String> getLDC(AbstractInsnNode node){
        List<String> list = new ArrayList<>();
        while(node!=null){
            if(node instanceof LdcInsnNode){
                String content = (String) ((LdcInsnNode)node).cst;
                list.add(content);
            }
            node=node.getNext();
        }
        return list;
    }

    private static String uglyClassName(String className){
        return className.replace('.','/');
    }

    private static Plugin extract(String classFile, List<String> parentClassNames, String sourceFile, String sourceFileDescRegularExpress) throws IOException {
        ClassNode classNode = new ClassNode();
        InputStream inputStream = new BufferedInputStream(new FileInputStream(classFile));
        ClassReader classReader = new ClassReader(inputStream);
        classReader.accept(classNode,0);
        if(!isEmpty(classNode.superName) && parentClassNames.contains(classNode.superName)){
            Plugin plugin = new Plugin();
            plugin.superName = classNode.superName;
            List<MethodNode> methodNodes= (List<MethodNode>) classNode.methods;
            if(methodNodes!=null && methodNodes.size()>0){
                    for(MethodNode methodNode:methodNodes){
                        // todo 需处理用Plugin用注解 设置名称的情况
                        if(methodNode.name.equals("getName") && methodNode.desc.equals("()Ljava/lang/String;")){
                                List<String> constants = getLDC(methodNode.instructions.getFirst());
                                if(constants.size()!=1){
                                    throw new IllegalStateException("处理类:"+classNode.name+"getName()方法出错，方法体应该有且只有一个常量");
                                }
                                plugin.name = constants.get(0);
                        }else if(methodNode.name.equals("getConstants") && methodNode.signature.equals("()Ljava/util/Map<Ljava/lang/String;Ljava/lang/Object;>;")) {
                                plugin.hasConstants = true;
                        }else if(methodNode.visibleAnnotations!=null  && methodNode.visibleAnnotations.size()>0){
                            boolean isReactMethod = false;
                            for(Object annotationNode: methodNode.visibleAnnotations){
                                AnnotationNode node  = (AnnotationNode) annotationNode;
                                if(node.desc.equals("Lcom/facebook/react/bridge/ReactMethod;")){
                                    isReactMethod = true;
                                    break;
                                }
                            }
                            if(isReactMethod){
                                List<String> paramTypes = new ArrayList<>();
                                List<String> paramNames = new ArrayList<>();
                                Type[] argumentTypes =  Type.getArgumentTypes(methodNode.desc);
                                for(Type type:argumentTypes){
                                    paramTypes.add(type.getClassName());
                                }
                                if(paramTypes.size()>0) {
                                    List<LocalVariableNode> parameterNodes = new ArrayList<>(methodNode.localVariables);
                                    parameterNodes.sort(new Comparator<LocalVariableNode>() {
                                        @Override
                                        public int compare(LocalVariableNode o1, LocalVariableNode o2) {
                                            return o1.index - o2.index;
                                        }
                                    });
                                    parameterNodes = parameterNodes.subList(1,paramTypes.size()+1);
                                    for(LocalVariableNode localVariableNode:parameterNodes){
                                        paramNames.add(localVariableNode.name);
                                    }
                                }
                                Plugin.Method method =new Plugin.Method();
                                method.name = methodNode.name;
                                method.paramNames = paramNames;
                                method.paramsTypes = paramTypes;
                                plugin.methods.add(method);
                            }
                        }
                    }
            }

//             创建 Pattern 对象
            if(!isEmpty(sourceFile) && !isEmpty(sourceFileDescRegularExpress)) {
                File sourceFILE = new File(sourceFile);
                if (sourceFILE.exists()) {
                    String source = readContent(sourceFile);
                    Pattern r = Pattern.compile(sourceFileDescRegularExpress);
                    // 现在创建 matcher 对象
                    Matcher m = r.matcher(source);
                    if (m.find() && m.groupCount()==1) {
                        String content = m.group(1);
                        plugin.description = content.trim();
                    }
                }
            }

            inputStream.close();
            return plugin;
        }
        inputStream.close();
        return null;
    }


    private static String readContent(String file) throws IOException{
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file),"utf8"));
        StringBuffer stringBuffer = new StringBuffer();
        String line;
        while((line = reader.readLine())!=null){
            stringBuffer.append(line);
            stringBuffer.append("\n");
        }
        reader.close();
        return stringBuffer.toString();

    }

    public static void main(String[] args){
        try {
            RNPlugins.extract1("C:\\Users\\laotian\\StudioProjects\\JDBApp\\jDB\\src\\main\\java",null,"C:\\Users\\laotian\\StudioProjects\\JDBApp\\jDB\\build\\intermediates\\classes\\debug\\",null,"d:\\manifest.js","d:\\plugins.js");
//            RNPlugins.extract("C:\\Users\\laotian\\StudioProjects\\JDBApp\\jDB\\build\\intermediates\\classes\\debug\\com\\rrh\\jdb\\reactnative\\plugins\\RNProxyDataModel.class", ,"C:\\Users\\laotian\\StudioProjects\\JDBApp\\jDB\\src\\main\\java\\com\\rrh\\jdb\\reactnative\\plugins\\RNProxyDataModel.java","@desc\\b+(.*)");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
