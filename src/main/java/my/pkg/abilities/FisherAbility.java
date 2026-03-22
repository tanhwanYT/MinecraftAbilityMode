package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class FisherAbility implements Ability {

    private final NamespacedKey rodKey;
    private final NamespacedKey fishKey;

    private static final double FISH_CHANCE = 0.35; // 35%
    private static final double FISH_HIT_DAMAGE = 4.0; // 2칸
    private static final double HEAL_AMOUNT = 4.0; // 2칸 회복

    private static final Map<UUID, Integer> trashCount = new ConcurrentHashMap<>();

    public FisherAbility(NamespacedKey rodKey, NamespacedKey fishKey) {
        this.rodKey = rodKey;
        this.fishKey = fishKey;
    }

    @Override
    public String id() {
        return "fisher";
    }

    @Override
    public String name() {
        return "피셔";
    }

    @Override
    public int cooldownSeconds() {
        return 0; // 패시브형
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("피셔 : 전용 낚싯대를 받습니다. 낚시에 성공하면 35% 확률로 물고기를 얻습니다.(실패하면 똥을 얻음)");
        player.sendMessage("피셔의 물고기 : 물고기로 때리면 고정 피해 2칸, 먹으면 체력을 회복합니다.");

        giveFishingRod(player);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("흠... 낚싯대가 또 필요하다고요? 분명 능력 지급할때 같이 줬을텐데?");
        giveFishingRod(player);
        return false;
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        removeFisherItems(player);
        trashCount.remove(player.getUniqueId());
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;

        ItemStack hand = attacker.getInventory().getItemInMainHand();
        if (!isFisherFish(hand)) return;

        // 물고기로 때리면 고정 피해 2칸
        event.setDamage(FISH_HIT_DAMAGE);

        attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_COD_FLOP, 0.8f, 1.0f);
        attacker.sendActionBar("§b[피셔] §f물고기 타격! 고정 피해 2칸");

        // ✅ 1회성 소비
        consumeOneFromMainHand(attacker);
    }

    @Override
    public void onFish(AbilitySystem system, PlayerFishEvent event) {
        Player player = event.getPlayer();

        // 전용 낚싯대가 아니면 무시
        ItemStack rod = player.getInventory().getItemInMainHand();
        if (!isFisherRod(rod)) return;

        // 낚시 성공 상태만
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;

        if (ThreadLocalRandom.current().nextDouble() < FISH_CHANCE) {
            ItemStack fish = createFishItem();
            player.getInventory().addItem(fish);
            player.sendMessage("§b[피셔] §f물고기를 낚았습니다!");
            player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 1.2f);
        } else {
            ItemStack trash = createTrashItem();
            player.getInventory().addItem(trash);

            int count = trashCount.getOrDefault(player.getUniqueId(), 0) + 1;
            trashCount.put(player.getUniqueId(), count);

            player.sendMessage("§6[피셔] §f낚시에 실패했습니다... §7똥을 건졌습니다. (§e" + count + "개)");

            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.9f);

            if (count % 5 == 0) {

                Bukkit.broadcastMessage(
                        "§6§l[피셔] §e" + player.getName() +
                                "§f님이 똥을 §c" + count +
                                "개§f나 낚았습니다ㅋㅋㅋㅋ"
                );

                ItemStack bonusFish = createFishItem();
                player.getInventory().addItem(bonusFish);

                player.playSound(player.getLocation(), Sound.ENTITY_COD_FLOP, 1.2f, 0.8f);
                player.sendMessage("§b[피셔] §f 똥 보너스! 신선한 물고기를 지급받았습니다.");
            }
        }
    }

    @Override
    public void onConsume(AbilitySystem system, PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!isFisherFish(item)) return;

        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        double max = (attr != null) ? attr.getValue() : 20.0;

        double newHealth = Math.min(max, player.getHealth() + HEAL_AMOUNT);
        player.setHealth(newHealth);

        player.sendMessage("§b[피셔] §f물고기를 먹고 체력을 회복했습니다!");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.8f, 1.0f);
    }

    private void giveFishingRod(Player player) {
        boolean hasRod = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isFisherRod(item)) {
                hasRod = true;
                break;
            }
        }

        if (!hasRod) {
            player.getInventory().addItem(createFishingRod());
        }
    }

    private ItemStack createTrashItem() {
        ItemStack item = new ItemStack(Material.BROWN_DYE, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6똥");
            meta.setLore(List.of("§7낚시에 실패해서 건진 쓰레기"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void consumeOneFromMainHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return;

        int amount = hand.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(amount - 1);
            player.getInventory().setItemInMainHand(hand);
        }
    }

    private void removeFisherItems(Player player) {

        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);

            if (item == null) continue;

            if (isFisherRod(item) || isFisherFish(item)) {
                player.getInventory().setItem(i, null);
            }
        }

        player.updateInventory();
    }

    private ItemStack createFishingRod() {
        ItemStack item = new ItemStack(Material.FISHING_ROD, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b피셔의 낚싯대");
            meta.setLore(List.of("§7농심 레드포스의 미드 라이너로,",
                    " 2025 LCK컵 4등에 준수한 도움을 준 피셔선수를 기리는 낚싯대"));
            meta.getPersistentDataContainer().set(rodKey, PersistentDataType.BYTE, (byte) 1);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createFishItem() {
        ItemStack item = new ItemStack(Material.COD, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b팔딱팔딱 신선한 물고기");
            meta.setLore(List.of(
                    "§7때리면 고정 피해 2칸",
                    "§7먹으면 체력 회복",
                    "§7참고로 1회성 아이템임"
            ));
            meta.getPersistentDataContainer().set(fishKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isFisherRod(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(rodKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private boolean isFisherFish(ItemStack item) {
        if (item == null || item.getType() != Material.COD || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(fishKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }
}