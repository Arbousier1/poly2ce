package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.common.impl.CommonImplUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public class PolymerConverterMod implements ModInitializer {
    public static final String MOD_ID = "polymer_converter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("PolymerConverter 初始化中...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("poly2ce")
                // 使用 Polymer 的权限工具类
                .requires(CommonImplUtils.permission("command.poly2ce", 4))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("开始全量转换..."), false);

                    int itemCount = runItemConversion();
                    int blockCount = runBlockConversion();
                    int entityCount = runEntityConversion();
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

    private int runItemConversion() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        Map<String, Object> itemsSection = new LinkedHashMap<>();
        int count = 0;

        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            var item = BuiltInRegistries.ITEM.getValue(id);
            if (item instanceof PolymerItem polymerItem) {
                String key = id.getNamespace() + ":" + id.getPath();
                itemsSection.put(key, ConverterLogic.convert(polymerItem));
                count++;
            }
        }
        rootConfig.put("items", itemsSection);
        writeConfig("converted_items.yml", rootConfig);
        return count;
    }

    private int runBlockConversion() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        Map<String, Object> blocksSection = new LinkedHashMap<>();
        int count = 0;

        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            if (block instanceof PolymerBlock polymerBlock) {
                String key = id.getNamespace() + ":" + id.getPath();
                blocksSection.put(key, BlockConverterLogic.convert(polymerBlock));
                count++;
            }
        }
        rootConfig.put("blocks", blocksSection);
        writeConfig("converted_blocks.yml", rootConfig);
        return count;
    }

    private int runEntityConversion() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        Map<String, Object> furnitureSection = new LinkedHashMap<>();
        int count = 0;

        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (id.getNamespace().equals("minecraft")) continue;

            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            Map<String, Object> config = EntityConverterLogic.convert(type);

            if (!config.isEmpty() && !config.containsKey("_note")) {
                String key = id.getNamespace() + ":" + id.getPath();
                furnitureSection.put(key, config);
                count++;
            }
        }
        rootConfig.put("furniture", furnitureSection);
        writeConfig("converted_furniture.yml", rootConfig);
        return count;
    }

    private int runSoundConversion() {
        Map<String, Object> config = SoundConverterLogic.convert();
        writeConfig("converted_sounds.yml", config);

        if (config.get("sounds") instanceof Map<?,?> map) {
            return map.size();
        }
        return 0;
    }

    private int runLangConversion() {
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
