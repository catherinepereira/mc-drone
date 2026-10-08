// approximate in-game colors, so maps of blocks read like the blocks themselves
export const BLOCK_COLORS: Record<string, string> = {
  "minecraft:oak_planks": "#b8945f",
  "minecraft:spruce_planks": "#7a5a34",
  "minecraft:birch_planks": "#d7cb8d",
  "minecraft:cobblestone": "#8a8a8a",
  "minecraft:bricks": "#965a4a",
  "minecraft:sandstone": "#dccf9e",
  "minecraft:white_wool": "#eaeded",
  "minecraft:red_wool": "#a12722",
  "minecraft:blue_wool": "#35399d",
  "minecraft:terracotta": "#985e43",
  "minecraft:cyan_concrete": "#157788",
  "minecraft:lime_concrete": "#5ea918",
  "minecraft:light_gray_concrete": "#c5c5bd",
  "minecraft:gray_concrete": "#36393d",
  "minecraft:stone_bricks": "#7a7a7a",
  "minecraft:stone": "#7d7d7d",
  "minecraft:tuff": "#6c6d66",
  "minecraft:gravel": "#837f7e",
  "minecraft:grass_block": "#6f9f42",
  "minecraft:dirt": "#866043",
  "minecraft:oak_log": "#6b5432",
  "minecraft:oak_leaves": "#4b7a2a",
  "minecraft:coal_ore": "#4a4a4a",
  "minecraft:chest": "#a2762b",
  "minecraft:farmland": "#5b3c22",
  "minecraft:water": "#3f76e4",
  "minecraft:wheat ripe": "#d8b44a",
  "minecraft:wheat growing": "#5f9e2f",
  "minecraft:wheat": "#b6a23a",
  "minecraft:glowstone": "#f2d16b",
  "minecraft:dripstone_block": "#86705c",
  "mcdrone:marker": "#e8892b",
};

export function blockColor(label: string): string {
  return BLOCK_COLORS[label] ?? "var(--color-text-muted)";
}

/** A block or item id without the minecraft namespace, spaced, such as "oak planks" */
export function shortName(label: string): string {
  return label.replace(/^minecraft:/, "").replaceAll("_", " ");
}
