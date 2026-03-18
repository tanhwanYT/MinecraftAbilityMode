package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class ScientistSecretItem implements SupplyItem, Listener {

    private final NamespacedKey itemIdKey;

    public ScientistSecretItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "scientist_secret";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b과학자의 비밀");
            meta.setLore(List.of("§7정체를 알 수 없는 불사의 토템"));
            meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());

            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isScientistSecret(ItemStack item) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        String value = meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        return id().equals(value);
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) return;
        if (event.isCancelled()) return;

        EntityEquipment eq = player.getEquipment();
        if (eq == null) return;

        ItemStack main = eq.getItemInMainHand();
        ItemStack off = eq.getItemInOffHand();

        // 메인핸드/오프핸드 중 실제로 사용된 커스텀 토템인지 확인
        if (!isScientistSecret(main) && !isScientistSecret(off)) return;

        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        // 부활 직후 처리되도록 1틱 뒤 적용
        JavaPlugin plugin = JavaPlugin.getProvidingPlugin(getClass());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            attr.setBaseValue(10.0); // 최대체력 10 = 하트 5칸
            if (player.getHealth() > 10.0) {
                player.setHealth(10.0);
            }
            player.sendMessage("§b[과학자의 비밀] §f부활의 대가로 최대 체력이 §c5칸§f이 되었습니다.");
        }, 1L);
    }
}