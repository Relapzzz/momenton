package com.relapzz.momenton.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.relapzz.momenton.Momenton;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MomentonConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("momenton.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final MomentonConfig INSTANCE = load();

	public boolean micEnabled = true;
	public String micDevice = "";

	public static MomentonConfig get() {
		return INSTANCE;
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			Momenton.LOGGER.warn("Could not save {}", FILE, e);
		}
	}

	private static MomentonConfig load() {
		if (!Files.exists(FILE)) {
			return new MomentonConfig();
		}
		try {
			return GSON.fromJson(Files.readString(FILE), MomentonConfig.class);
		} catch (IOException | JsonParseException e) {
			Momenton.LOGGER.warn("Could not read {}, using the defaults", FILE, e);
			return new MomentonConfig();
		}
	}
}
