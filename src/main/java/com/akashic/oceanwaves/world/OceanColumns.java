package com.akashic.oceanwaves.world;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.IBlockAccess;

/**
 * Que colunas são mar aberto: a regra que o servidor (física) e o cliente (desenho, e a face de cima da água vanilla
 * que some) usam igual. Lê só o {@link IBlockAccess} que recebe, então roda nas threads de montagem de chunks do
 * Angelica sem tocar no mundo.
 */
public final class OceanColumns {

    /** Altura da face de cima de uma fonte de água no bloco (8/9, a do vanilla e a do Neo States). */
    public static final double SOURCE_TOP = 8.0 / 9.0;

    private OceanColumns() {}

    /**
     * O bloco (x, y, z) é a água da superfície do mar aberto: fonte de água (parada ou recém-mexida, metadado 0) com
     * ar em cima.
     */
    public static boolean isOpenSea(IBlockAccess w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        if (b != Blocks.water && b != Blocks.flowing_water) {
            return false;
        }
        if (w.getBlockMetadata(x, y, z) != 0) {
            return false;
        }
        return w.getBlock(x, y + 1, z)
            .getMaterial() == Material.air;
    }

    /** Profundidade em blocos de água abaixo da superfície (até {@code max}). */
    public static int depth(IBlockAccess w, int x, int y, int z, int max) {
        int d = 0;
        while (d < max && y - d >= 0
            && w.getBlock(x, y - d, z)
                .getMaterial() == Material.water) {
            d++;
        }
        return d;
    }
}
