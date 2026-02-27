package my.pkg;

import my.pkg.abilities.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin {
    private AbilitySystem abilitySystem;
    private GameManager gameManager;
    private SupplyManager supplyManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.supplyManager = new SupplyManager(this);
        getServer().getPluginManager().registerEvents(this.supplyManager, this);

        this.gameManager = new GameManager(this.supplyManager, this);
        getServer().getPluginManager().registerEvents(this.gameManager, this);

        this.abilitySystem = new AbilitySystem(this, this.gameManager);

        SupplyCommand supplyCommand = new SupplyCommand(this.supplyManager);
        getCommand("supply").setExecutor(supplyCommand);
        getCommand("supply").setTabCompleter(supplyCommand);

        abilitySystem.register(new MalphiteAbility());
        abilitySystem.register(new ViperAbility());
        abilitySystem.register(new KiyathowAbility());
        abilitySystem.register(new BombermanAbility());
        abilitySystem.register(new AntmanAbility());
        abilitySystem.register(new SpeedingAbility());
        abilitySystem.register(new GamblerAbility());
        abilitySystem.register(new SniperAbility(this));
        abilitySystem.register(new donationAbility(this));
        abilitySystem.register(new TaliyahAbility(this));
        abilitySystem.register(new JokerAbility());
        abilitySystem.register(new PanicAbility());
        abilitySystem.register(new GlowAbility());
        abilitySystem.register(new BodyguardAbility(this));
        abilitySystem.register(new HitmanAbility(this));

        // 6) ability 리스너/커맨드
        abilitySystem.registerListeners();
        getCommand("ability").setExecutor(abilitySystem);
    }

    @Override
    public void onDisable() {
        if (abilitySystem != null) {
            abilitySystem.shutdown();
        }
    }
}
