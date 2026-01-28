package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
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
            dispatcher.register(CommandManager.literal("poly2ce")
                .requires(source -> source.hasPermissionLevel(4)) // 需要 OP 权限
                .executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal("开始全量转换..."), false);
                    
                    // 1. 物品
                    int itemCount = runItemConversion();
                    // 2. 方块
                    int blockCount = runBlockConversion();
                    // 3. 实体 (家具)
                    int entityCount = runEntityConversion();
                    // 4. 声音
                    int soundCount = runSoundConversion();
                    // 5. 语言 (Lang)
                    int langCount = runLangConversion();
                    
                    context.getSource().sendFeedback(() -> Text.literal(
                        String.format("转换完成！\n物品: %d\n方块: %d\n实体: %d\n声音: %d\n语言条目: %d", 
                        itemCount, blockCount, entityCount, soundCount, langCount)
                    ), true);
                    
                    context.getSource().sendFeedback(() -> Text.literal("文件已保存至 config/craft-engine/ 目录"), false);
                    return 1;
                }));
        });
    }

    private int runItemConversion() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        Map<String, Object> itemsSection = new LinkedHashMap<>();
        int count = 0;

        for (Identifier id : Registries.ITEM.getIds()) {
            if (Registries.ITEM.get(id) instanceof PolymerItem polymerItem) {
                String key = id.getNamespace() + ":" + id.getPath();
                // 调用 ConverterLogic (物品)
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

        for (Identifier id : Registries.BLOCK.getIds()) {
            Block block = Registries.BLOCK.get(id);
            if (block instanceof PolymerBlock polymerBlock) {
                String key = id.getNamespace() + ":" + id.getPath();
                // 调用 BlockConverterLogic (方块)
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

        for (Identifier id : Registries.ENTITY_TYPE.getIds()) {
            // 跳过原版实体
            if (id.getNamespace().equals("minecraft")) continue;

            EntityType<?> type = Registries.ENTITY_TYPE.get(id);
            // 调用 EntityConverterLogic (家具)
            Map<String, Object> config = EntityConverterLogic.convert(type);
            
            // 如果返回结果包含错误或有效配置，则写入
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
        // 调用 SoundConverterLogic (声音)
        Map<String, Object> config = SoundConverterLogic.convert();
        writeConfig("converted_sounds.yml", config);
        
        if (config.get("sounds") instanceof Map<?,?> map) {
            return map.size();
        }
        return 0;
    }

    private int runLangConversion() {
        // 调用 LanguageConverterLogic (语言)
        Map<String, Object> config = LanguageConverterLogic.convert();
        writeConfig("converted_lang.yml", config);
        
        int count = 0;
        try {
            Map<?,?> items = (Map<?,?>) ((Map<?,?>) config.get("lang#items")).get("en_us");
            Map<?,?> blocks = (Map<?,?>) ((Map<?,?>) config.get("lang#blocks")).get("en_us");
            count = items.size() + blocks.size();
        } catch (Exception ignored) {}
        return count;
    }

    private void writeConfig(String fileName, Map<String, Object> data) {
        try {
            Path configDir = Paths.get("config", "craft-engine");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            Path outputPath = configDir.resolve(fileName);
            // 使用 SimpleYamlWriter
            Files.writeString(outputPath, SimpleYamlWriter.dump(data));
            LOGGER.info("已生成: " + fileName);
        } catch (IOException e) {
            LOGGER.error("写入失败: " + fileName, e);
        }
    }
}