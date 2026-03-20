package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class ScientistSecretItem implements SupplyItem {

    private final NamespacedKey itemIdKey;
    private final java.util.Set<java.util.UUID> processing = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
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

    @Override
    public void onResurrect(JavaPlugin plugin, EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.isCancelled()) return;

        if (!processing.add(player.getUniqueId())) return;

        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            processing.remove(player.getUniqueId());
            return;
        }

        ItemStack usedTotem = switch (hand) {
            case HAND -> player.getInventory().getItemInMainHand();
            case OFF_HAND -> player.getInventory().getItemInOffHand();
            default -> null;
        };

        if (!isScientistSecret(usedTotem)) {
            processing.remove(player.getUniqueId());
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                if (!player.isOnline() || player.isDead()) return;
                // 디버프
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.WEAKNESS, 20 * 15, 0, true, true, true));
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.SLOWNESS, 20 * 7, 0, true, true, true));

                player.sendMessage("§b[과학자의 비밀] §f부활의 대가로 몸이 불안정해졌습니다!");
            } finally {
                processing.remove(player.getUniqueId());
            }
        }, 1L);
    }
}