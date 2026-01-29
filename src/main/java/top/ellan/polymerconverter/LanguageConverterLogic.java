package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import xyz.nucleoid.packettweaker.PacketContext;

// Mojang 映射标准导入
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import net.minecraft.core.registries.BuiltInRegistries;
// 使用 Identifier
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public class LanguageConverterLogic {

    public static Map<String, Object> convert() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        
        Map<String, String> itemLangEn = new TreeMap<>();
        Map<String, String> blockLangEn = new TreeMap<>();
        
        PacketContext ctx = PacketContext.create();

        // 1. 提取物品名称 (lang#items)
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getNamespace().equals("minecraft")) continue;

            // FIX: 使用 getValue(id) 直接获取 Item 对象
            Item item = BuiltInRegistries.ITEM.getValue(id);
            
            if (item instanceof PolymerItem) {
                String key = "item." + id.getNamespace() + "." + id.getPath();
                
                // 获取 Polymer 转换后的客户端堆栈
                ItemStack clientStack = PolymerItemUtils.getPolymerItemStack(
                    item.getDefaultInstance(), 
                    TooltipFlag.NORMAL, 
                    ctx
                );
                
                // 获取显示名称
                String name = clientStack.getHoverName().getString();
                
                itemLangEn.put(key, "<!i>" + name);
            }
        }

        // 2. 提取方块名称 (lang#blocks)
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            if (id.getNamespace().equals("minecraft")) continue;

            // FIX: 使用 getValue(id) 直接获取 Block 对象
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            
            if (block instanceof PolymerBlock) {
                String key = "block_name:" + id.getNamespace() + ":" + id.getPath();
                
                // 获取名称
                String name = block.getName().getString();
                
                blockLangEn.put(key, "<!i>" + name);
            }
        }

        // 3. 组装配置结构
        Map<String, Object> itemsSection = new LinkedHashMap<>();
        itemsSection.put("en_us", itemLangEn);
        itemsSection.put("zh_cn", new LinkedHashMap<>(itemLangEn)); 
        rootConfig.put("lang#items", itemsSection);

        Map<String, Object> blocksSection = new LinkedHashMap<>();
        blocksSection.put("en_us", blockLangEn);
        blocksSection.put("zh_cn", new LinkedHashMap<>(blockLangEn));
        rootConfig.put("lang#blocks", blocksSection);
        
        return rootConfig;
    }
}