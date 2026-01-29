package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject; // [新增]
import xyz.nucleoid.packettweaker.PacketContext;

// Mojang 映射标准导入
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import net.minecraft.core.registries.BuiltInRegistries;
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

            Item item = BuiltInRegistries.ITEM.getValue(id);
            
            // [修复] 检查是否是 Polymer 物品 (支持 Overlay)
            // 逻辑：如果它是 PolymerItem 实例 OR 它有注册的 Overlay
            boolean isPolymer = (item instanceof PolymerItem) || 
                                (PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, item) != null);

            if (isPolymer) {
                String key = "item." + id.getNamespace() + "." + id.getPath();
                
                // PolymerItemUtils.getPolymerItemStack 会自动处理 Overlay 的情况
                ItemStack clientStack = PolymerItemUtils.getPolymerItemStack(
                    item.getDefaultInstance(), 
                    TooltipFlag.NORMAL, 
                    ctx
                );
                
                if (clientStack != null) {
                    // 获取显示名称
                    String name = clientStack.getHoverName().getString();
                    itemLangEn.put(key, "<!i>" + name);
                }
            }
        }

        // 2. 提取方块名称 (lang#blocks)
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            if (id.getNamespace().equals("minecraft")) continue;

            Block block = BuiltInRegistries.BLOCK.getValue(id);
            
            // [修复] 检查是否是 Polymer 方块 (支持 Overlay)
            boolean isPolymer = (block instanceof PolymerBlock) || 
                                (PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block) != null);
            
            if (isPolymer) {
                String key = "block_name:" + id.getNamespace() + ":" + id.getPath();
                
                // 获取默认状态的名称
                String name = block.getName().getString();
                
                // 也可以尝试获取转换后的方块名称 (虽然通常方块名称由原始方块决定)
                // BlockState visualState = PolymerBlockUtils.getPolymerBlockState(block.defaultBlockState(), ctx);
                // String visualName = visualState.getBlock().getName().getString();
                
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