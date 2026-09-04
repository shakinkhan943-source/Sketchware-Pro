package pro.sketchware.core.codegen;

import android.util.Pair;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import pro.sketchware.beans.BlockBean;
import pro.sketchware.beans.ComponentBean;
import pro.sketchware.beans.ProjectFileBean;
import pro.sketchware.core.project.BuildConfig;
import pro.sketchware.core.project.ProjectDataStore;

/**
 * Generates a real Kotlin Jetpack Compose Activity.
 *
 * <p>Project logic continues to use the historical {@code FooActivity.java} storage key, but every
 * emitted declaration, lifecycle event and block body is Kotlin and the resulting file is
 * {@code FooActivity.kt}. Compose Activities never read or inflate an XML layout.</p>
 */
public final class KotlinActivityCodeGenerator {

    private final BuildConfig buildConfig;
    private final ProjectFileBean projectFile;
    private final ProjectDataStore dataStore;
    private final String logicKey;
    private final Set<String> imports = new LinkedHashSet<>();

    public KotlinActivityCodeGenerator(BuildConfig buildConfig, ProjectFileBean projectFile,
                                       ProjectDataStore dataStore) {
        if (projectFile == null || !projectFile.isComposeActivity()) {
            throw new IllegalArgumentException("KotlinActivityCodeGenerator requires a Compose Activity");
        }
        this.buildConfig = buildConfig;
        this.projectFile = projectFile;
        this.dataStore = dataStore;
        logicKey = projectFile.getJavaName();
        addDefaultImports();
    }

    public String generateCode(String scId) {
        return generateCode(scId, true);
    }

    /**
     * @param applyFormatting whether to run the common brace-aware source formatter
     */
    public String generateCode(String scId, boolean applyFormatting) {
        CodeContext codeContext = new CodeContext(projectFile.getActivityName(), false);
        EventCodeGenerator events = new EventCodeGenerator(buildConfig, projectFile, dataStore, codeContext);
        collectProjectImports(scId, events);
        addComponentLifecycleEvents(events);

        String lifecycleJava = events.generateActivityLifecycleEventCode();
        String customImports = KotlinCodeConverter.convertImports(LogicHandler.imports(lifecycleJava));
        String lifecycleCode = KotlinCodeConverter.convertMembers(LogicHandler.base(lifecycleJava));

        StringBuilder fields = new StringBuilder();
        appendVariablesAndLists(fields, scId);
        ArrayList<ComponentBean> components = dataStore.getComponents(logicKey);
        appendComponentFields(fields, components, scId);
        appendRequestCodeFields(fields, components);

        String initializationBlocks = interpret("initializeLogic_initializeLogic");
        String onCreateBlocks = interpret("onCreate_initializeLogic");
        String componentInitialization = createComponentInitialization(components, codeContext);
        String componentListeners = joinNonEmpty(
                KotlinCodeConverter.convertMembers(events.generateComponentEvents()),
                KotlinCodeConverter.convertMembers(events.generateAuthEvents()));

        String activityResult = createActivityResult(events);
        String extraEventMembers = joinNonEmpty(
                KotlinCodeConverter.convertMembers(events.eventLogic),
                KotlinCodeConverter.convertMembers(events.eventListenerCode));
        String moreBlocks = createMoreBlocks();

        StringBuilder source = new StringBuilder(8192);
        source.append("package ").append(buildConfig.packageName).append(ActivityCodeGenerator.EOL)
                .append(ActivityCodeGenerator.EOL);
        for (String importName : imports) {
            if (importName == null || importName.trim().isEmpty()) continue;
            String normalized = importName.trim();
            if (normalized.startsWith("import ")) normalized = normalized.substring(7).trim();
            if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1);
            source.append("import ").append(normalized).append(ActivityCodeGenerator.EOL);
        }
        if (!customImports.isEmpty()) {
            source.append(customImports).append(ActivityCodeGenerator.EOL);
        }
        source.append(ActivityCodeGenerator.EOL)
                .append("class ").append(projectFile.getActivityName())
                .append(" : ComponentActivity() {").append(ActivityCodeGenerator.EOL);

        appendSection(source, fields.toString());
        source.append(ActivityCodeGenerator.EOL)
                .append("override fun onCreate(_savedInstanceState: Bundle?) {")
                .append(ActivityCodeGenerator.EOL)
                .append("super.onCreate(_savedInstanceState);")
                .append(ActivityCodeGenerator.EOL)
                .append("setContent {").append(ActivityCodeGenerator.EOL)
                .append("SketchwareTheme {").append(ActivityCodeGenerator.EOL)
                .append(projectFile.getActivityName()).append("Screen()").append(ActivityCodeGenerator.EOL)
                .append("}").append(ActivityCodeGenerator.EOL)
                .append("}").append(ActivityCodeGenerator.EOL);
        if (buildConfig.isFirebaseEnabled) {
            source.append("FirebaseApp.initializeApp(this);").append(ActivityCodeGenerator.EOL);
        }
        source.append("initialize(_savedInstanceState);").append(ActivityCodeGenerator.EOL)
                .append("initializeLogic();").append(ActivityCodeGenerator.EOL)
                .append("}").append(ActivityCodeGenerator.EOL)
                .append(ActivityCodeGenerator.EOL)
                .append("@Composable").append(ActivityCodeGenerator.EOL)
                .append("private fun ").append(projectFile.getActivityName()).append("Screen() {")
                .append(ActivityCodeGenerator.EOL)
                .append("Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {")
                .append(ActivityCodeGenerator.EOL)
                .append("Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {")
                .append(ActivityCodeGenerator.EOL)
                .append("Text(text = \"").append(escapeKotlinString(projectFile.fileName)).append("\")")
                .append(ActivityCodeGenerator.EOL)
                .append("}").append(ActivityCodeGenerator.EOL)
                .append("}").append(ActivityCodeGenerator.EOL)
                .append("}").append(ActivityCodeGenerator.EOL)
                .append(ActivityCodeGenerator.EOL)
                .append("private fun initialize(_savedInstanceState: Bundle?) {")
                .append(ActivityCodeGenerator.EOL);
        appendSection(source, initializationBlocks);
        appendSection(source, componentInitialization);
        appendSection(source, componentListeners);
        source.append("}").append(ActivityCodeGenerator.EOL)
                .append(ActivityCodeGenerator.EOL)
                .append("private fun initializeLogic() {").append(ActivityCodeGenerator.EOL);
        appendSection(source, onCreateBlocks);
        source.append("}").append(ActivityCodeGenerator.EOL);

        appendSection(source, activityResult);
        appendSection(source, lifecycleCode);
        appendSection(source, extraEventMembers);
        appendSection(source, moreBlocks);
        source.append("}").append(ActivityCodeGenerator.EOL);

        String code = source.toString();
        return applyFormatting ? CodeFormatter.formatCode(code, false) : code;
    }

    private void addDefaultImports() {
        imports.add("android.app.*");
        imports.add("android.content.*");
        imports.add("android.content.res.*");
        imports.add("android.graphics.*");
        imports.add("android.graphics.drawable.*");
        imports.add("android.media.*");
        imports.add("android.net.*");
        imports.add("android.os.*");
        imports.add("android.text.*");
        imports.add("android.text.style.*");
        imports.add("android.util.*");
        imports.add("android.view.*");
        imports.add("android.view.animation.*");
        imports.add("android.webkit.*");
        imports.add("android.widget.*");
        imports.add("java.io.*");
        imports.add("java.text.*");
        imports.add("java.util.*");
        imports.add("java.util.regex.*");
        imports.add("org.json.*");
        imports.add("androidx.activity.ComponentActivity");
        imports.add("androidx.activity.compose.setContent");
        imports.add("androidx.compose.foundation.layout.Box");
        imports.add("androidx.compose.foundation.layout.fillMaxSize");
        imports.add("androidx.compose.material3.MaterialTheme");
        imports.add("androidx.compose.material3.Surface");
        imports.add("androidx.compose.material3.Text");
        imports.add("androidx.compose.runtime.Composable");
        imports.add("androidx.compose.ui.Alignment");
        imports.add("androidx.compose.ui.Modifier");
        if (buildConfig.isFirebaseEnabled) imports.add("com.google.firebase.FirebaseApp");
    }

    private void collectProjectImports(String scId, EventCodeGenerator events) {
        imports.addAll(events.getImports());
        for (Pair<Integer, String> variable : dataStore.getVariables(logicKey)) {
            if (variable.first == 9 && variable.second != null && !variable.second.trim().isEmpty()) {
                imports.add(variable.second);
            } else if (variable.first != 6) {
                imports.addAll(ComponentTypeMapper.getImportsByTypeName(scId,
                        ComponentTypeMapper.getVariableTypeName(variable.first), null));
            }
        }
        for (Pair<Integer, String> list : dataStore.getListVariables(logicKey)) {
            imports.addAll(ComponentTypeMapper.getImportsByTypeName(scId,
                    ComponentTypeMapper.getListInternalName(list.first), null));
        }
        for (ComponentBean component : dataStore.getComponents(logicKey)) {
            imports.addAll(ComponentTypeMapper.getImportsByTypeName(scId,
                    ComponentTypeMapper.getComponentTypeName(component.type), null));
        }
        for (Map.Entry<String, ArrayList<BlockBean>> entry : dataStore.getBlockMap(logicKey).entrySet()) {
            for (BlockBean block : entry.getValue()) addImportsForBlock(block.opCode);
        }
    }

    private void addImportsForBlock(String opcode) {
        if (opcode == null) return;
        switch (opcode) {
            case "toStringWithDecimal", "toStringFormat" -> imports.add("java.text.DecimalFormat");
            case "strToListMap", "strToListStr", "strToMap",
                    "GsonStringToListString", "GsonStringToListNumber" -> {
                imports.add("com.google.gson.Gson");
                imports.add("com.google.gson.reflect.TypeToken");
            }
            case "mapToStr", "listMapToStr", "GsonListTojsonString" ->
                    imports.add("com.google.gson.Gson");
            case "setTypeface" -> imports.add("android.graphics.Typeface");
            case "copyToClipboard", "getClipboard" -> {
                imports.add("android.content.ClipData");
                imports.add("android.content.ClipboardManager");
            }
            case "fileutilGetLastSegmentPath" -> imports.add("android.net.Uri");
            case "setImageUrl" -> imports.add("com.bumptech.glide.Glide");
            case "interstitialAdLoad", "rewardedVideoAdLoad" -> {
                imports.add("com.google.android.gms.ads.AdRequest");
                imports.add("com.google.android.gms.ads.LoadAdError");
            }
            default -> {
                // Extra/custom blocks carry imports through user Import blocks. Their executable
                // template is still translated by KotlinCodeConverter.
            }
        }
    }

    private void appendVariablesAndLists(StringBuilder fields, String scId) {
        for (Pair<Integer, String> variable : dataStore.getVariables(logicKey)) {
            int type = variable.first;
            String name = variable.second;
            if (name == null || name.trim().isEmpty() || type == 9) continue;
            String javaField;
            if (type == 6) {
                javaField = name.endsWith(";") ? name : name + ";";
            } else {
                javaField = ComponentCodeGenerator.getFieldDeclaration(
                        ComponentTypeMapper.getVariableTypeName(type), name,
                        ComponentCodeGenerator.AccessModifier.PRIVATE);
            }
            appendSection(fields, KotlinCodeConverter.convertMembers(javaField));
        }
        for (Pair<Integer, String> list : dataStore.getListVariables(logicKey)) {
            String javaField = ComponentCodeGenerator.getFieldDeclaration(
                    ComponentTypeMapper.getListInternalName(list.first), list.second,
                    ComponentCodeGenerator.AccessModifier.PRIVATE);
            appendSection(fields, KotlinCodeConverter.convertMembers(javaField));
        }
    }

    private void appendComponentFields(StringBuilder fields, ArrayList<ComponentBean> components,
                                       String scId) {
        boolean timer = false;
        boolean firebase = false;
        boolean storage = false;
        boolean interstitial = false;
        boolean rewarded = false;
        for (ComponentBean component : components) {
            String type = ComponentTypeMapper.getComponentTypeName(component.type);
            String javaField = ComponentCodeGenerator.getFieldDeclaration(type,
                    component.componentId, ComponentCodeGenerator.AccessModifier.PRIVATE,
                    component.param1, component.param2, component.param3);
            appendSection(fields, KotlinCodeConverter.convertMembers(javaField));
            timer |= component.type == ComponentBean.COMPONENT_TYPE_TIMERTASK;
            firebase |= component.type == ComponentBean.COMPONENT_TYPE_FIREBASE;
            storage |= component.type == ComponentBean.COMPONENT_TYPE_FIREBASE_STORAGE;
            interstitial |= component.type == ComponentBean.COMPONENT_TYPE_INTERSTITIAL_AD;
            rewarded |= component.type == ComponentBean.COMPONENT_TYPE_REWARDED_VIDEO_AD;
        }
        if (timer) appendConvertedStaticField(fields, "Timer");
        if (firebase) appendConvertedStaticField(fields, "FirebaseDB");
        if (storage) appendConvertedStaticField(fields, "FirebaseStorage");
        if (interstitial) appendConvertedStaticField(fields, "InterstitialAd");
        if (rewarded) appendConvertedStaticField(fields, "RewardedVideoAd");
    }

    private void appendConvertedStaticField(StringBuilder fields, String type) {
        appendSection(fields, KotlinCodeConverter.convertMembers(
                ComponentCodeGenerator.getComponentFieldCode(type)));
    }

    private void appendRequestCodeFields(StringBuilder fields, ArrayList<ComponentBean> components) {
        int requestCode = 100;
        for (ComponentBean component : components) {
            if (component.type == ComponentBean.COMPONENT_TYPE_CAMERA
                    || component.type == ComponentBean.COMPONENT_TYPE_FILE_PICKER
                    || component.type == ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH_GOOGLE_LOGIN) {
                requestCode++;
                appendSection(fields, "private val REQ_CD_"
                        + component.componentId.toUpperCase(Locale.ROOT) + ": Int = " + requestCode);
            }
        }
    }

    private String createComponentInitialization(ArrayList<ComponentBean> components,
                                                 CodeContext context) {
        StringBuilder result = new StringBuilder();
        for (ComponentBean component : components) {
            String javaCode = ComponentCodeGenerator.getComponentInitializerCode(context,
                    ComponentTypeMapper.getComponentTypeName(component.type), component.componentId,
                    component.param1, component.param2, component.param3);
            appendSection(result, KotlinCodeConverter.convertBlock(javaCode));
        }
        return result.toString();
    }

    private void addComponentLifecycleEvents(EventCodeGenerator events) {
        for (ComponentBean component : dataStore.getComponents(logicKey)) {
            if (component.type == ComponentBean.COMPONENT_TYPE_SQLITE) {
                events.addLifecycleEvent("onDestroy", "SQLiteDatabase", component.componentId);
            } else if (component.type == ComponentBean.COMPONENT_TYPE_GYROSCOPE) {
                events.addLifecycleEvent("onDestroy", "Gyroscope", component.componentId);
            }
        }
    }

    private String createActivityResult(EventCodeGenerator events) {
        String directLogic = interpret("onActivityResult_onActivityResult");
        String callbacks = events.getOnActivityResultSwitchCases();
        if (directLogic.trim().isEmpty() && callbacks.trim().isEmpty()) return "";
        StringBuilder javaMethod = new StringBuilder()
                .append("@Override").append(ActivityCodeGenerator.EOL)
                .append("protected void onActivityResult(int _requestCode, int _resultCode, Intent _data) {")
                .append(ActivityCodeGenerator.EOL)
                .append("super.onActivityResult(_requestCode, _resultCode, _data);")
                .append(ActivityCodeGenerator.EOL)
                .append(directLogic).append(ActivityCodeGenerator.EOL);
        if (!callbacks.trim().isEmpty()) {
            javaMethod.append("switch (_requestCode) {").append(ActivityCodeGenerator.EOL)
                    .append(callbacks).append(ActivityCodeGenerator.EOL)
                    .append("default:").append(ActivityCodeGenerator.EOL)
                    .append("break;").append(ActivityCodeGenerator.EOL)
                    .append("}").append(ActivityCodeGenerator.EOL);
        }
        javaMethod.append("}");
        return KotlinCodeConverter.convertMembers(javaMethod.toString());
    }

    private String createMoreBlocks() {
        StringBuilder result = new StringBuilder();
        for (Pair<String, String> moreBlock : dataStore.getMoreBlocks(logicKey)) {
            String ownerKey = moreBlock.first + "_moreBlock";
            String logic = interpret(ownerKey);
            String javaMethod = ComponentCodeGenerator.getMoreBlockCode(
                    moreBlock.first, moreBlock.second, logic);
            appendSection(result, KotlinCodeConverter.convertMembers(javaMethod));
        }
        return result.toString();
    }

    private String interpret(String ownerKey) {
        ArrayList<BlockBean> blocks = dataStore.getBlocks(logicKey, ownerKey);
        return new BlockInterpreter(projectFile.getActivityName(), buildConfig, blocks, false,
                null, BlockInterpreter.SourceLanguage.KOTLIN).interpretBlocks(ownerKey);
    }

    private static String joinNonEmpty(String... sections) {
        StringBuilder result = new StringBuilder();
        for (String section : sections) appendSection(result, section);
        return result.toString();
    }

    private static void appendSection(StringBuilder target, String section) {
        if (section == null || section.trim().isEmpty()) return;
        if (target.length() > 0 && target.charAt(target.length() - 1) != '\n') {
            target.append(ActivityCodeGenerator.EOL);
        }
        target.append(section.trim()).append(ActivityCodeGenerator.EOL);
    }

    private static String escapeKotlinString(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
