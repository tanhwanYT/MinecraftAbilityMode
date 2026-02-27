package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class GamblerDiamondItem implements SupplyItem {

    private final NamespacedKey itemIdKey;

    public GamblerDiamondItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "gambler_diamond";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack it = new ItemStack(Material.DIAMOND, 1);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§d도박꾼의 다이아몬드");
        meta.setLore(List.of(
                "§7타격 시:",
                "§a30%§7 상대 즉사",
                "§c70%§7 자신 사망"
        ));
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
        it.setItemMeta(meta);
        return it;
    }

    @Override
    public void onHitEntity(JavaPlugin plugin, Player attacker, LivingEntity victim, EntityDamageByEntityEvent e) {
        // 이 아이템은 "한 번 휘두르면 결과가 전부"라서 기본 데미지 무의미 → 취소
        e.setCancelled(true);

        boolean win = ThreadLocalRandom.current().nextInt(100) < 30;

        if (win) {
            victim.setHealth(0.0);
            attacker.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            attacker.sendMessage("§a[도박] 성공! 상대를 처치했다!");
        } else {
            attacker.setHealth(0.0);
            if (attacker.getWorld() != null) attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.0f);
        }

        // 1회성 소비
        consumeOne(attacker);
    }

    private void consumeOne(Player attacker) {
        ItemStack hand = attacker.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() != Material.DIAMOND) return;
        int amt = hand.getAmount();
        if (amt <= 1) attacker.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else hand.setAmount(amt - 1);
    }
}