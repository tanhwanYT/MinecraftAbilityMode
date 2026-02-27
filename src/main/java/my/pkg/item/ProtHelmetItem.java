package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class ProtHelmetItem implements SupplyItem {

    private final NamespacedKey itemIdKey;

    public ProtHelmetItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "prot_helmet";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack it = new ItemStack(Material.DIAMOND_HELMET, 1);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§b다이아몬드 투구 §7(보호 I)");
        meta.setLore(List.of("§7보호 I 이 부여되어 있습니다."));
        meta.addEnchant(Enchantment.PROTECTION, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
        it.setItemMeta(meta);
        return it;
    }
}