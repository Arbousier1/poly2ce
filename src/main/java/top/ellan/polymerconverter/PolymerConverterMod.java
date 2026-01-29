package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.common.impl.CommonImplUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder; // [新增] 需要导入 Holder
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
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings({"null"})
public class PolymerConverterMod implements ModInitializer {
    public static final String MOD_ID = "polymer_converter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("PolymerConverter 初始化中...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("poly2ce")
                .requires(CommonImplUtils.permission("command.poly2ce", 4))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("开始全量转换..."), false);

                    // 获取当前的 ServerLevel
                    ServerLevel level = context.getSource().getLevel();

                    int itemCount = runItemConversion(level);
                    int blockCount = runBlockConversion(level);
                    int entityCount = runEntityConversion(level);
                    
                    int soundCount = runSoundConversion();
                    int langCount = runLangConversion();

                    context.getSource().sendSuccess(() -> Component.literal(
                        String.format("转换完成！\n物品: %d\n方块: %d\n实体: %d\n声音: %d\n语言条目: %d",
                        itemCount, blockCount, entityCount, soundCount, langCount)
                    ), true);

                    context.getSource().sendSuccess(() -> Component.literal("文件已保存至 config/craft-engine/ 目录"), false);
                    return 1;
                }));
        });
    }

    private int runItemConversion(ServerLevel level) {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        Map<String, Object> itemsSection = new LinkedHashMap<>();
        int count = 0;

        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            // [修复] 解包 Optional<Holder.Reference<Item>>
            Item item = BuiltInRegistries.ITEM.get(id)
                .map(Holder::value)
                .orElse(null);

            if (item == null) continue;

            PolymerItem polymerLogic = null;

            if (item instanceof PolymerItem pi) {
                polymerLogic = pi;
            } 
            else {
                Object synced = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, item);
                if (synced instanceof PolymerItem pi) {
                    polymerLogic = pi;
                }
            }

            if (polymerLogic != null) {
                String key = id.getNamespace() + ":" + id.getPath();
                try {
                    itemsSection.put(key, ConverterLogic.convert(item, polymerLogic, level));
                    count++;
                } catch (Exception e) {
                    LOGGER.error("Error converting item: " + key, e);
                }
            }
        }
        rootConfig.put("items", itemsSection);
        writeConfig("converted_items.yml", rootConfig);
        return count;
    }

    private int runBlockConversion(ServerLevel level) {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        Map<String, Object> blocksSection = new LinkedHashMap<>();
        int count = 0;

        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            // [修复] 解包 Optional<Holder.Reference<Block>>
            Block block = BuiltInRegistries.BLOCK.get(id)
                .map(Holder::value)
                .orElse(null);
            
            if (block == null) continue;

            PolymerBlock polymerLogic = null;

            if (block instanceof PolymerBlock pb) {
                polymerLogic = pb;
            } 
            else {
                Object synced = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block);
                if (synced instanceof PolymerBlock pb) {
                    polymerLogic = pb;
                }
            }

            if (polymerLogic != null) {
                String key = id.getNamespace() + ":" + id.getPath();
                try {
                    blocksSection.put(key, BlockConverterLogic.convert(block, polymerLogic, level));
                    count++;
                } catch (Exception e) {
                    LOGGER.error("Error converting block: " + key, e);
                }
            }
        }
        rootConfig.put("blocks", blocksSection);
        writeConfig("converted_blocks.yml", rootConfig);
        return count;
    }

    private int runEntityConversion(ServerLevel level) {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        Map<String, Object> furnitureSection = new LinkedHashMap<>();
        int count = 0;

        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (id.getNamespace().equals("minecraft")) continue;

            // [修复] 解包 Optional<Holder.Reference<EntityType<?>>>
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id)
                .map(Holder::value)
                .orElse(null);

            if (type == null) continue;

            try {
                Map<String, Object> config = EntityConverterLogic.convert(type, level);

                if (!config.isEmpty() && !config.containsKey("_note")) {
                    String key = id.getNamespace() + ":" + id.getPath();
                    furnitureSection.put(key, config);
                    count++;
                }
            } catch (Exception e) {
                // Ignore entities that fail conversion
                LOGGER.debug("Skipping entity " + id + ": " + e.getMessage());
            }
        }
        rootConfig.put("furniture", furnitureSection);
        writeConfig("converted_furniture.yml", rootConfig);
        return count;
    }

    private int runSoundConversion() {
        try {
            Map<String, Object> config = SoundConverterLogic.convert();
            writeConfig("converted_sounds.yml", config);

            if (config.get("sounds") instanceof Map<?,?> map) {
                return map.size();
            }
        } catch (Exception e) {
            LOGGER.error("Sound conversion failed", e);
        }
        return 0;
    }

    private int runLangConversion() {
        try {
            Map<String, Object> config = LanguageConverterLogic.convert();
            writeConfig("converted_lang.yml", config);

            int count = 0;
            try {
                Object itemsObj = config.get("lang#items");
                if (itemsObj instanceof Map<?,?> itemsMap) {
                    Object enUsItems = itemsMap.get("en_us");
                    if (enUsItems instanceof Map<?,?> items) {
                        count += items.size();
                    }
                }

                Object blocksObj = config.get("lang#blocks");
                if (blocksObj instanceof Map<?,?> blocksMap) {
                    Object enUsBlocks = blocksMap.get("en_us");
                    if (enUsBlocks instanceof Map<?,?> blocks) {
                        count += blocks.size();
                    }
                }
            } catch (Exception ignored) {
            }
            return count;
        } catch (Exception e) {
            LOGGER.error("Language conversion failed", e);
        }
        return 0;
    }

    private void writeConfig(String fileName, Map<String, Object> data) {
        try {
            Path configDir = Paths.get("config", "craft-engine");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            Path outputPath = configDir.resolve(fileName);
            Files.writeString(outputPath, SimpleYamlWriter.dump(data));
            LOGGER.info("已生成: " + fileName);
        } catch (IOException e) {
            LOGGER.error("写入失败: " + fileName, e);
        }
    }
}