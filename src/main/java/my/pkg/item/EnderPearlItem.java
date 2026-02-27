package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class EnderPearlItem implements SupplyItem {

    @Override
    public String id() {
        return "ender_pearl";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        // 순수 바닐라로 주는 게 스택/사용성 제일 좋음 (PDC 안 붙임)
        return new ItemStack(Material.ENDER_PEARL, 1);
    }
}