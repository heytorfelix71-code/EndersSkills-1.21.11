package dev.enderskills.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

/** Mod Menu "Config" button (needs YACL; without it the settings stay in config/enderskills/settings.json). */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3") ? EsConfigScreen.create(parent) : null;
	}
}
