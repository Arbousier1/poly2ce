package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.CommonImplUtils;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PolymerConverterMod implements ModInitializer {
    public static final String MOD_ID = "polymer_converter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private Map<String, Object> lastConvertedItems = new LinkedHashMap<>();
    private Map<String, Object> furnitureFromBlocks = new LinkedHashMap<>();

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing poly2ce converter...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("poly2ce")
                .requires(CommonImplUtils.permission("command.poly2ce", 4))
                .executes(context -> {
                    ServerLevel level = context.getSource().getLevel();
                    context.getSource().sendSuccess(() -> Component.literal("poly2ce: converting Polymer data to CraftEngine configs..."), false);
                    cleanPreviousOutputs();

                    int itemCount = runItemConversion(level);
                    int blockCount = runBlockConversion(level);
                    int furnitureCount = runEntityConversion(level);
                    int soundCount = runSoundConversion();
                    int langCount = runLangConversion();
                    int recipeCount = runRecipeConversion(level);
                    int categoryCount = runCategoryConversion();
                    exportCeConfigPack();

                    context.getSource().sendSuccess(() -> Component.literal(
                        "poly2ce complete. items=" + itemCount
                            + ", blocks=" + blockCount
                            + ", furniture=" + furnitureCount
                            + ", sounds=" + soundCount
                            + ", lang_entries=" + langCount
                            + ", recipes=" + recipeCount
                            + ", categories=" + categoryCount
                    ), true);
                    context.getSource().sendSuccess(() -> Component.literal("Files written to config/craft-engine/ and plugins/CraftEngine/resources/poly2ce"), false);
                    return 1;
                }))
        );
    }

    private void cleanPreviousOutputs() {
        Path ceRoot = Paths.get("config", "craft-engine");
        try {
            deleteDirectory(ceRoot.resolve("generated"));
            deleteDirectory(ceRoot.resolve("generated-pack"));
        } catch (IOException e) {
            LOGGER.warn("Failed to clean previous generated outputs", e);
        }
    }

    private int runItemConversion(ServerLevel level) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> items = new LinkedHashMap<>();
        int count = 0;

        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == null) {
                continue;
            }

            PolymerItem polymerItem = null;
            if (item instanceof PolymerItem asPolymer) {
                polymerItem = asPolymer;
            } else {
                Object overlay = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, item);
                if (overlay instanceof PolymerItem asPolymer) {
                    polymerItem = asPolymer;
                }
            }

            if (polymerItem == null) {
                continue;
            }

            String key = id.getNamespace() + ":" + id.getPath();
            try {
                Map<String, Object> converted = ConverterLogic.convert(item, polymerItem, level);
                if (!converted.isEmpty()) {
                    items.put(key, converted);
                    count++;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to convert item {}", key, e);
            }
        }

        root.put("items", items);
        writeConfig("converted_items.yml", root);
        writeSplitSection("items", "items", items);
        this.lastConvertedItems = new LinkedHashMap<>(items);
        return count;
    }

    private int runBlockConversion(ServerLevel level) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> blocks = new LinkedHashMap<>();
        Map<String, Object> blockFurniture = new LinkedHashMap<>();
        Set<String> furnitureIds = new HashSet<>();
        int count = 0;

        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            if (block == null) {
                continue;
            }

            PolymerBlock polymerBlock = null;
            if (block instanceof PolymerBlock asPolymer) {
                polymerBlock = asPolymer;
            } else {
                Object overlay = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block);
                if (overlay instanceof PolymerBlock asPolymer) {
                    polymerBlock = asPolymer;
                }
            }

            if (polymerBlock == null) {
                continue;
            }

            String key = id.getNamespace() + ":" + id.getPath();
            try {
                if (BlockConverterLogic.shouldConvertAsFurniture(block)) {
                    Map<String, Object> furniture = BlockConverterLogic.convertAsFurniture(block, polymerBlock, level);
                    if (!furniture.isEmpty()) {
                        blockFurniture.put(key, furniture);
                        furnitureIds.add(key);
                        count++;
                    }
                } else {
                    Map<String, Object> converted = BlockConverterLogic.convert(block, polymerBlock, level);
                    if (!converted.isEmpty()) {
                        blocks.put(key, converted);
                        count++;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to convert block {}", key, e);
            }
        }

        root.put("blocks", blocks);
        writeConfig("converted_blocks.yml", root);
        writeSplitSection("blocks", "blocks", blocks);
        this.furnitureFromBlocks = blockFurniture;
        applyFurnitureItemBehaviorOverrides(furnitureIds, blockFurniture);
        return count;
    }

    private int runEntityConversion(ServerLevel level) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> furniture = new LinkedHashMap<>();
        int count = 0;

        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if ("minecraft".equals(id.getNamespace())) {
                continue;
            }

            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            if (type == null) {
                continue;
            }

            try {
                Map<String, Object> cfg = EntityConverterLogic.convert(type, level);
                if (!cfg.isEmpty()) {
                    furniture.putIfAbsent(id.getNamespace() + ":" + id.getPath(), cfg);
                    count++;
                }
            } catch (Exception e) {
                LOGGER.debug("Skipping entity {}: {}", id, e.getMessage());
            }
        }

        root.put("furniture", furniture);
        writeConfig("converted_furniture.yml", root);
        writeSplitSection("furniture", "furniture", furniture);
        return furniture.size();
    }

    private void applyFurnitureItemBehaviorOverrides(Set<String> furnitureIds, Map<String, Object> furnitureMap) {
        if (furnitureIds.isEmpty()) {
            return;
        }
        for (String id : furnitureIds) {
            @SuppressWarnings("unchecked")
            Map<String, Object> itemCfg = (Map<String, Object>) this.lastConvertedItems.get(id);
            if (itemCfg == null) {
                itemCfg = new LinkedHashMap<>();
                itemCfg.put("material", "minecraft:paper");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("item_name", "<!i>" + id);
                itemCfg.put("data", data);
                this.lastConvertedItems.put(id, itemCfg);
            }
            itemCfg.put("behavior", furnitureItemBehavior(id, furnitureMap.get(id)));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("items", this.lastConvertedItems);
        writeConfig("converted_items.yml", root);
        writeSplitSection("items", "items", this.lastConvertedItems);
    }

    private static Map<String, Object> furnitureItemBehavior(String furnitureId, Object furnitureConfig) {
        Map<String, Object> behavior = new LinkedHashMap<>();
        behavior.put("type", "furniture_item");
        if (furnitureConfig instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            behavior.put("furniture", typed);
        } else {
            behavior.put("furniture", furnitureId);
        }

        Map<String, Object> rules = new LinkedHashMap<>();
        Map<String, Object> ground = new LinkedHashMap<>();
        ground.put("rotation", "any");
        ground.put("alignment", "any");
        rules.put("ground", ground);
        behavior.put("rules", rules);
        return behavior;
    }

    private int runSoundConversion() {
        try {
            Map<String, Object> root = SoundConverterLogic.convert();
            writeConfig("converted_sounds.yml", root);

            Object sounds = root.get("sounds");
            if (sounds instanceof Map<?, ?> map) {
                return map.size();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to convert sounds", e);
        }
        return 0;
    }

    private int runLangConversion() {
        try {
            Map<String, Object> root = LanguageConverterLogic.convert();
            writeConfig("converted_lang.yml", root);
            writeI18nFiles(root);

            int count = 0;
            count += nestedCount(root, "lang#items", "en_us");
            count += nestedCount(root, "lang#blocks", "en_us");
            count += nestedCount(root, "lang#furniture", "en_us");
            count += nestedCount(root, "i18n", "en_us");
            return count;
        } catch (Exception e) {
            LOGGER.error("Failed to convert language", e);
            return 0;
        }
    }

    private int runRecipeConversion(ServerLevel level) {
        try {
            Map<String, Object> root = RecipeConverterLogic.convert(level);
            writeConfig("converted_recipes.yml", root);

            Object recipesObj = root.get("recipes");
            if (recipesObj instanceof Map<?, ?> recipesMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) recipesMap;
                writeSplitSection("recipes", "recipes", typed);
                return recipesMap.size();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to convert recipes", e);
        }
        return 0;
    }

    private int runCategoryConversion() {
        try {
            if (this.lastConvertedItems == null || this.lastConvertedItems.isEmpty()) {
                return 0;
            }

            Map<String, Object> grouped = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : this.lastConvertedItems.entrySet()) {
                String id = entry.getKey();
                String namespace = namespaceOf(id);
                if (namespace.isBlank() || "unknown".equals(namespace)) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> ids = (Map<String, Object>) grouped.computeIfAbsent(namespace, ignored -> {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("ids", new java.util.ArrayList<String>());
                    return one;
                });
                @SuppressWarnings("unchecked")
                java.util.List<String> idList = (java.util.List<String>) ids.get("ids");
                idList.add(id);
            }

            Map<String, Object> categories = new LinkedHashMap<>();
            int priority = 1;
            for (Map.Entry<String, Object> entry : grouped.entrySet()) {
                String namespace = entry.getKey();
                @SuppressWarnings("unchecked")
                Map<String, Object> bucket = (Map<String, Object>) entry.getValue();
                @SuppressWarnings("unchecked")
                java.util.List<String> ids = (java.util.List<String>) bucket.get("ids");
                if (ids == null || ids.isEmpty()) {
                    continue;
                }

                Map<String, Object> category = new LinkedHashMap<>();
                category.put("name", "<!i><white>" + namespace + "</white>");
                category.put("lore", java.util.List.of());
                category.put("hidden", false);
                category.put("priority", priority++);
                category.put("icon", ids.get(0));
                category.put("list", ids);
                categories.put(namespace + ":" + namespace, category);
            }

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("categories", categories);
            writeConfig("converted_categories.yml", root);
            writeSplitSection("categories", "categories", categories);
            return categories.size();
        } catch (Exception e) {
            LOGGER.error("Failed to convert categories", e);
            return 0;
        }
    }

    private static int nestedCount(Map<String, Object> root, String sectionKey, String localeKey) {
        Object sectionObj = root.get(sectionKey);
        if (!(sectionObj instanceof Map<?, ?> section)) {
            return 0;
        }
        Object localeObj = section.get(localeKey);
        if (!(localeObj instanceof Map<?, ?> locale)) {
            return 0;
        }
        return locale.size();
    }

    private void writeI18nFiles(Map<String, Object> root) {
        Object i18nObj = root.get("i18n");
        if (!(i18nObj instanceof Map<?, ?> i18nMap)) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> typedI18n = (Map<String, Object>) i18nMap;
        Map<String, Object> i18nRoot = new LinkedHashMap<>();
        // CraftEngine parses both "i18n" and "translations" as translation sections.
        // Emitting both in one file will register duplicated keys.
        i18nRoot.put("translations", typedI18n);
        writeConfig("converted_i18n.yml", i18nRoot);
        writeSplitI18nByNamespace(typedI18n);
    }

    private void writeSplitI18nByNamespace(Map<String, Object> i18nLocales) {
        Map<String, Map<String, Object>> perNamespacePerLocale = new LinkedHashMap<>();

        for (Map.Entry<String, Object> localeEntry : i18nLocales.entrySet()) {
            String locale = localeEntry.getKey();
            if (!(localeEntry.getValue() instanceof Map<?, ?> values)) {
                continue;
            }
            for (Map.Entry<?, ?> kv : values.entrySet()) {
                if (!(kv.getKey() instanceof String key) || kv.getValue() == null) {
                    continue;
                }
                String namespace = namespaceFromLangKey(key);
                Map<String, Object> localeMap = perNamespacePerLocale.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>());
                @SuppressWarnings("unchecked")
                Map<String, Object> oneLocaleValues = (Map<String, Object>) localeMap.computeIfAbsent(locale, ignored -> new LinkedHashMap<String, Object>());
                oneLocaleValues.put(key, kv.getValue());
            }
        }

        Path dir = Paths.get("config", "craft-engine", "generated", "i18n");
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, Map<String, Object>> entry : perNamespacePerLocale.entrySet()) {
                String namespace = sanitizeFileName(entry.getKey());
                Map<String, Object> locales = entry.getValue();

                Map<String, Object> out = new LinkedHashMap<>();
                // Keep only one canonical section to avoid duplicate registration.
                out.put("translations", locales);

                Path file = dir.resolve(namespace).resolve(namespace + ".yml");
                Files.createDirectories(file.getParent());
                Files.writeString(file, SimpleYamlWriter.dump(out));
            }
            LOGGER.info("Wrote {} split i18n files", perNamespacePerLocale.size());
        } catch (IOException e) {
            LOGGER.error("Failed to write split i18n files", e);
        }
    }

    private static String namespaceFromLangKey(String key) {
        String[] parts = key.split("\\.");
        if (parts.length >= 2) {
            String ns = parts[1];
            if (!ns.isBlank()) {
                return ns;
            }
        }
        return "unknown";
    }

    private void writeConfig(String fileName, Map<String, Object> data) {
        try {
            Path dir = Paths.get("config", "craft-engine");
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, SimpleYamlWriter.dump(data));
            LOGGER.info("Wrote {}", fileName);
        } catch (IOException e) {
            LOGGER.error("Failed to write {}", fileName, e);
        }
    }

    private void writeSplitSection(String subDir, String rootKey, Map<String, Object> entries) {
        try {
            Path dir = Paths.get("config", "craft-engine", "generated", subDir);
            Files.createDirectories(dir);

            entries.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> {
                    String id = entry.getKey();
                    Object value = entry.getValue();
                    String namespace = namespaceOf(id);
                    String localPath = pathOf(id);

                    Map<String, Object> oneEntry = new LinkedHashMap<>();
                    oneEntry.put(id, value);

                    Map<String, Object> root = new LinkedHashMap<>();
                    root.put(rootKey, oneEntry);

                    String fileName = sanitizeFileName(localPath) + ".yml";
                    Path out = dir.resolve(namespace).resolve(fileName);
                    try {
                        Files.createDirectories(out.getParent());
                        Files.writeString(out, SimpleYamlWriter.dump(root));
                    } catch (IOException e) {
                        LOGGER.error("Failed to write split config {}", out, e);
                    }
                });

            LOGGER.info("Wrote {} split {} files", entries.size(), subDir);
        } catch (IOException e) {
            LOGGER.error("Failed to write split section {}", subDir, e);
        }
    }

    private static String sanitizeFileName(String id) {
        return id
            .replace(':', '_')
            .replace('/', '_')
            .replace('\\', '_')
            .replace('*', '_')
            .replace('?', '_')
            .replace('\"', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('|', '_');
    }

    private static String namespaceOf(String id) {
        int idx = id.indexOf(':');
        if (idx <= 0) {
            return "unknown";
        }
        return sanitizeFileName(id.substring(0, idx));
    }

    private static String pathOf(String id) {
        int idx = id.indexOf(':');
        if (idx < 0 || idx + 1 >= id.length()) {
            return id;
        }
        return id.substring(idx + 1);
    }

    private void exportCeConfigPack() {
        Path ceRoot = Paths.get("config", "craft-engine");
        Path splitRoot = ceRoot.resolve("generated");
        Path packRoot = ceRoot.resolve(Paths.get("generated-pack", "poly2ce"));
        Path packConfig = packRoot.resolve("configuration");
        Path zipPath = ceRoot.resolve(Paths.get("generated-pack", "poly2ce.zip"));
        Path ceResourcesRoot = Paths.get("plugins", "CraftEngine", "resources");
        Path ceRuntimePackRoot = ceResourcesRoot.resolve("poly2ce");

        try {
            deleteDirectory(packRoot);
            Files.createDirectories(packConfig);

            writeText(packRoot.resolve("pack.yml"),
                "author: poly2ce\n"
                    + "version: 1.0.0\n"
                    + "description: Auto exported Polymer -> CraftEngine configuration pack\n"
                    + "namespace: poly2ce\n");

            copyDirectory(splitRoot.resolve("items"), packConfig.resolve("items"));
            copyDirectory(splitRoot.resolve("blocks"), packConfig.resolve("blocks"));
            copyDirectory(splitRoot.resolve("furniture"), packConfig.resolve("furniture"));
            copyDirectory(splitRoot.resolve("recipes"), packConfig.resolve("recipes"));
            copyDirectory(splitRoot.resolve("categories"), packConfig.resolve("categories"));
            copyDirectory(splitRoot.resolve("i18n"), packConfig.resolve("i18n"));
            copyReferencedAssets(packRoot);

            // Do not copy converted_lang.yml/converted_i18n.yml into the pack configuration.
            // They would duplicate keys that already exist in split i18n namespace files.

            Path soundsSrc = ceRoot.resolve("converted_sounds.yml");
            if (Files.exists(soundsSrc)) {
                Files.copy(soundsSrc, packConfig.resolve("sounds.yml"), StandardCopyOption.REPLACE_EXISTING);
            }

            Files.createDirectories(zipPath.getParent());
            createZipFromDirectory(packRoot, zipPath);
            deleteDirectory(ceRuntimePackRoot);
            copyDirectory(packRoot, ceRuntimePackRoot);
            LOGGER.info("Exported CE config pack: {} and {}", packRoot, zipPath);
            LOGGER.info("Exported CE runtime pack folder: {}", ceRuntimePackRoot);
        } catch (Exception e) {
            LOGGER.error("Failed to export CE config pack", e);
        }
    }

    private void copyReferencedAssets(Path packRoot) {
        Set<String> namespaces = collectReferencedNamespaces();
        Path assetsRoot = packRoot.resolve("assets");
        try {
            Files.createDirectories(assetsRoot);
        } catch (IOException e) {
            LOGGER.warn("Failed to create assets root {}", assetsRoot, e);
            return;
        }

        int polymerCopied = copyAssetsFromPolymerGeneratedPack(packRoot);
        int copiedCount = 0;
        for (String namespace : namespaces) {
            if (copyNamespaceAssets(namespace, assetsRoot.resolve(namespace))) {
                copiedCount++;
            }
        }
        LOGGER.info(
            "Copied {} files from Polymer generated pack; copied namespace assets for {}/{} namespaces into generated pack",
            polymerCopied,
            copiedCount,
            namespaces.size()
        );
    }

    private int copyAssetsFromPolymerGeneratedPack(Path packRoot) {
        Path generatedPackZip = Paths.get("config", "craft-engine", "generated-pack", "polymer-resource-pack.zip");
        try {
            Files.createDirectories(generatedPackZip.getParent());
            if (Files.exists(generatedPackZip)) {
                Files.delete(generatedPackZip);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed preparing temp Polymer resource pack path {}", generatedPackZip, e);
            return 0;
        }

        boolean generated = PolymerResourcePackUtils.buildMain(
            generatedPackZip,
            status -> LOGGER.debug("[polymer-rp] {}", status)
        );
        if (!generated || !Files.exists(generatedPackZip)) {
            LOGGER.warn("Failed to generate Polymer resource pack at {}", generatedPackZip);
            return 0;
        }

        int copied = 0;
        try {
            copied = copyAssetsFromZip(generatedPackZip, packRoot.resolve("assets"));
        } catch (IOException e) {
            LOGGER.warn("Failed to merge Polymer generated resource pack {}", generatedPackZip, e);
        }

        try {
            Files.deleteIfExists(generatedPackZip);
        } catch (IOException ignored) {
        }
        return copied;
    }

    private static int copyAssetsFromZip(Path zipPath, Path targetAssetsRoot) throws IOException {
        int[] copied = new int[] {0};
        try (FileSystem zipFs = FileSystems.newFileSystem(zipPath)) {
            Path assetsRoot = zipFs.getPath("/assets");
            if (!Files.exists(assetsRoot)) {
                return 0;
            }

            try (Stream<Path> stream = Files.walk(assetsRoot)) {
                stream.forEach(path -> {
                    Path relative = assetsRoot.relativize(path);
                    Path destination = targetAssetsRoot.resolve(relative.toString());
                    try {
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.createDirectories(destination.getParent());
                            Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                            copied[0]++;
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
        return copied[0];
    }

    private Set<String> collectReferencedNamespaces() {
        Set<String> namespaces = new HashSet<>();

        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (!"minecraft".equals(id.getNamespace())) {
                namespaces.add(id.getNamespace());
            }
        }
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            if (!"minecraft".equals(id.getNamespace())) {
                namespaces.add(id.getNamespace());
            }
        }
        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (!"minecraft".equals(id.getNamespace())) {
                namespaces.add(id.getNamespace());
            }
        }
        for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
            if (!"minecraft".equals(id.getNamespace())) {
                namespaces.add(id.getNamespace());
            }
        }

        return namespaces;
    }

    private boolean copyNamespaceAssets(String namespace, Path targetDir) {
        boolean copiedAny = false;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (Path rootPath : mod.getRootPaths()) {
                Path source = rootPath.resolve("assets").resolve(namespace);
                if (!Files.exists(source)) {
                    continue;
                }
                try {
                    copyDirectory(source, targetDir);
                    copiedAny = true;
                } catch (IOException e) {
                    LOGGER.warn("Failed to copy assets namespace {} from mod {}", namespace, mod.getMetadata().getId(), e);
                }
            }
        }
        return copiedAny;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(path -> {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static void createZipFromDirectory(Path sourceDir, Path zipFile) throws IOException {
        Files.deleteIfExists(zipFile);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile));
             Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(path -> !Files.isDirectory(path)).forEach(path -> {
                Path relative = sourceDir.relativize(path);
                String entryName = relative.toString().replace('\\', '/');
                try {
                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.write(Files.readAllBytes(path));
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static void writeText(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
