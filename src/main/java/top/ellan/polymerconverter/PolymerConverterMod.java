package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.CommonImplUtils;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PolymerConverterMod implements ModInitializer {
    public static final String MOD_ID = "polymer_converter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing poly2ce converter...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("poly2ce")
                .requires(CommonImplUtils.permission("command.poly2ce", 4))
                .executes(context -> {
                    ServerLevel level = context.getSource().getLevel();
                    context.getSource().sendSuccess(() -> Component.literal("poly2ce: converting Polymer data to CraftEngine configs..."), false);

                    int itemCount = runItemConversion(level);
                    int blockCount = runBlockConversion(level);
                    int furnitureCount = runEntityConversion(level);
                    int soundCount = runSoundConversion();
                    int langCount = runLangConversion();
                    exportCeConfigPack();

                    context.getSource().sendSuccess(() -> Component.literal(
                        "poly2ce complete. items=" + itemCount
                            + ", blocks=" + blockCount
                            + ", furniture=" + furnitureCount
                            + ", sounds=" + soundCount
                            + ", lang_entries=" + langCount
                    ), true);
                    context.getSource().sendSuccess(() -> Component.literal("Files written to config/craft-engine/ and generated-pack/poly2ce(.zip)"), false);
                    return 1;
                }))
        );
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
        return count;
    }

    private int runBlockConversion(ServerLevel level) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> blocks = new LinkedHashMap<>();
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
                Map<String, Object> converted = BlockConverterLogic.convert(block, polymerBlock, level);
                if (!converted.isEmpty()) {
                    blocks.put(key, converted);
                    count++;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to convert block {}", key, e);
            }
        }

        root.put("blocks", blocks);
        writeConfig("converted_blocks.yml", root);
        writeSplitSection("blocks", "blocks", blocks);
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
                    furniture.put(id.getNamespace() + ":" + id.getPath(), cfg);
                    count++;
                }
            } catch (Exception e) {
                LOGGER.debug("Skipping entity {}: {}", id, e.getMessage());
            }
        }

        root.put("furniture", furniture);
        writeConfig("converted_furniture.yml", root);
        writeSplitSection("furniture", "furniture", furniture);
        return count;
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

            int count = 0;
            count += nestedCount(root, "lang#items", "en_us");
            count += nestedCount(root, "lang#blocks", "en_us");
            count += nestedCount(root, "lang#furniture", "en_us");
            return count;
        } catch (Exception e) {
            LOGGER.error("Failed to convert language", e);
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

            Path langSrc = ceRoot.resolve("converted_lang.yml");
            if (Files.exists(langSrc)) {
                Files.copy(langSrc, packConfig.resolve("lang.yml"), StandardCopyOption.REPLACE_EXISTING);
            }

            Path soundsSrc = ceRoot.resolve("converted_sounds.yml");
            if (Files.exists(soundsSrc)) {
                Files.copy(soundsSrc, packConfig.resolve("sounds.yml"), StandardCopyOption.REPLACE_EXISTING);
            }

            Files.createDirectories(zipPath.getParent());
            createZipFromDirectory(packRoot, zipPath);
            LOGGER.info("Exported CE config pack: {} and {}", packRoot, zipPath);
        } catch (Exception e) {
            LOGGER.error("Failed to export CE config pack", e);
        }
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
