package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class MidasHandItem implements SupplyItem {

    private final NamespacedKey itemIdKey;

    public MidasHandItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "midas_hand";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack it = new ItemStack(Material.GOLDEN_SWORD, 1);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§6미다스의 손");
        meta.setLore(List.of("§7타격 시 상대의 랜덤 방어구를", "§e금 방어구§7로 바꿉니다"));
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
        // 🔥 내구도 거의 끝 상태로 설정
        if (meta instanceof Damageable dmg) {
            dmg.setDamage(it.getType().getMaxDurability() - 1);
        }
        it.setItemMeta(meta);
        return it;
    }

    @Override
    public void onHitEntity(JavaPlugin plugin, Player attacker, LivingEntity victim, EntityDamageByEntityEvent e) {
        if (!(victim instanceof Player target)) return;

        EquipmentSlot slot = switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> EquipmentSlot.HEAD;
            case 1 -> EquipmentSlot.CHEST;
            case 2 -> EquipmentSlot.LEGS;
            default -> EquipmentSlot.FEET;
        };

        ItemStack old = getArmor(target, slot);
        if (old == null || old.getType().isAir()) return;

        Material newMat = switch (slot) {
            case HEAD -> Material.GOLDEN_HELMET;
            case CHEST -> Material.GOLDEN_CHESTPLATE;
            case LEGS -> Material.GOLDEN_LEGGINGS;
            case FEET -> Material.GOLDEN_BOOTS;
            default -> null;
        };
        if (newMat == null) return;

        ItemStack gold = new ItemStack(newMat, 1);

        // 인챈트 복사
        gold.addUnsafeEnchantments(old.getEnchantments());

        // 내구도 비율 비슷하게 유지
        if (old.getItemMeta() instanceof Damageable od && gold.getItemMeta() instanceof Damageable gd) {
            int oldMax = old.getType().getMaxDurability();
            int newMax = gold.getType().getMaxDurability();
            if (oldMax > 0 && newMax > 0) {
                double ratio = (double) od.getDamage() / (double) oldMax;
                gd.setDamage((int) Math.round(ratio * newMax));
                gold.setItemMeta((ItemMeta) gd);
            }
        }

        setArmor(target, slot, gold);

        attacker.playSound(attacker.getLocation(), Sound.ENTITY_ZOMBIFIED_PIGLIN_ANGRY, 0.7f, 1.4f);
        target.playSound(target.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.2f);
        attacker.sendMessage("§6[미다스] 상대의 방어구가 금으로 변했습니다!");
        target.sendMessage("§e[미다스] 방어구가 금으로 변했습니다!");

        attacker.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        attacker.playSound(attacker.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
    }

    private ItemStack getArmor(Player p, EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> p.getInventory().getHelmet();
            case CHEST -> p.getInventory().getChestplate();
            case LEGS -> p.getInventory().getLeggings();
            case FEET -> p.getInventory().getBoots();
            default -> null;
        };
    }

    private void setArmor(Player p, EquipmentSlot slot, ItemStack item) {
        switch (slot) {
            case HEAD -> p.getInventory().setHelmet(item);
            case CHEST -> p.getInventory().setChestplate(item);
            case LEGS -> p.getInventory().setLeggings(item);
            case FEET -> p.getInventory().setBoots(item);
        }
    }

    private void damageMainHand(Player p, int amount) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return;
        if (!(hand.getItemMeta() instanceof Damageable dmg)) return;

        dmg.setDamage(dmg.getDamage() + amount);
        hand.setItemMeta((ItemMeta) dmg);

        if (hand.getType().getMaxDurability() > 0 && dmg.getDamage() >= hand.getType().getMaxDurability()) {
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        }
    }
}