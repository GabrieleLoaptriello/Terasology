/*
 * Copyright 2013 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.core.world.generator.chunkGenerators;

import org.terasology.math.TeraMath;
import org.terasology.registry.CoreRegistry;
import org.terasology.utilities.procedural.BrownianNoise3D;
import org.terasology.utilities.procedural.Noise3D;
import org.terasology.utilities.procedural.PerlinNoise;
import org.terasology.world.WorldBiomeProvider;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockManager;
import org.terasology.world.chunks.ChunkConstants;
import org.terasology.world.chunks.CoreChunk;
import org.terasology.world.generator.ChunkGenerationPass;
import org.terasology.world.liquid.LiquidData;
import org.terasology.world.liquid.LiquidType;

import javax.vecmath.Vector2f;
import java.util.Map;

/**
 * Terasology's legacy map generator. Still rocks!
 *
 * @author Benjamin Glatzel <benjamin.glatzeo@me.com>
 */
public class PerlinTerrainGenerationPass implements ChunkGenerationPass {
    private static final int SAMPLE_RATE_3D_HOR = 4;
    private static final int SAMPLE_RATE_3D_VERT = 4;

    private Noise3D pGen1;
    private Noise3D pGen2;
    private Noise3D pGen3;
    private Noise3D pGen4;
    private Noise3D pGen5;
    private Noise3D pGen8;
    private WorldBiomeProvider biomeProvider;

    private Block air;
    private Block water;
    private Block ice;
    private Block stone;
    private Block sand;
    private Block grass;
    private Block snow;
    private Block dirt;

    public PerlinTerrainGenerationPass() {
        BlockManager blockManager = CoreRegistry.get(BlockManager.class);
        air = BlockManager.getAir();
        water = blockManager.getBlock("core:water");
        ice = blockManager.getBlock("core:Ice");
        stone = blockManager.getBlock("core:Stone");
        sand = blockManager.getBlock("core:Sand");
        grass = blockManager.getBlock("core:Grass");
        snow = blockManager.getBlock("core:Snow");
        dirt = blockManager.getBlock("core:Dirt");
    }

    @Override
    public void setWorldSeed(String seed) {
        if (seed != null) {
            pGen1 = new BrownianNoise3D(new PerlinNoise(seed.hashCode()), 8);

            pGen2 = new BrownianNoise3D(new PerlinNoise(seed.hashCode() + 1), 8);

            pGen3 = new BrownianNoise3D(new PerlinNoise(seed.hashCode() + 2), 8);

            pGen4 = new BrownianNoise3D(new PerlinNoise(seed.hashCode() + 3));
            pGen5 = new BrownianNoise3D(new PerlinNoise(seed.hashCode() + 4));
            pGen8 = new BrownianNoise3D(new PerlinNoise(seed.hashCode() + 7));
        }
    }

    @Override
    public void setWorldBiomeProvider(WorldBiomeProvider value) {
        this.biomeProvider = value;
    }

    @Override
    public void generateChunk(CoreChunk chunk) {
        double[][][] densityMap = new double[chunk.getChunkSizeX() + 1][chunk.getChunkSizeY() + 1][chunk.getChunkSizeZ() + 1];

        /*
         * Create the density map at a lower sample rate.
         */
        for (int x = 0; x <= chunk.getChunkSizeX(); x += SAMPLE_RATE_3D_HOR) {
            for (int z = 0; z <= chunk.getChunkSizeZ(); z += SAMPLE_RATE_3D_HOR) {
                for (int y = 0; y <= chunk.getChunkSizeY(); y += SAMPLE_RATE_3D_VERT) {
                    densityMap[x][y][z] = calcDensity(chunk.chunkToWorldPositionX(x), chunk.chunkToWorldPositionY(y), chunk.chunkToWorldPositionZ(z));
                }
            }
        }

        /*
         * Trilinear interpolate the missing values.
         */
        triLerpDensityMap(densityMap);

        /*
         * Generate the chunk from the density map.
         */
        for (int x = 0; x < chunk.getChunkSizeX(); x++) {
            for (int z = 0; z < chunk.getChunkSizeZ(); z++) {
                WorldBiomeProvider.Biome type = biomeProvider.getBiomeAt(chunk.chunkToWorldPositionX(x), chunk.chunkToWorldPositionZ(z));
                int firstBlockHeight = -1;

                int yOffset = chunk.getChunkSizeY() * chunk.getPosition().y;

                for (int y = chunk.getChunkSizeY() - 1; y >= 0; y--) {
                    int yPos = yOffset + y;
                    if (yPos <= 32) { // Ocean
                        chunk.setBlock(x, y, z, water);
                        chunk.setLiquid(x, y, z, new LiquidData(LiquidType.WATER, LiquidData.MAX_LIQUID_DEPTH));

                        if (yPos == 32) {
                            // Ice layer
                            if (type == WorldBiomeProvider.Biome.SNOW) {
                                chunk.setBlock(x, y, z, ice);
                            }
                        }
                    }

                    double dens = densityMap[x][y][z];

                    if ((dens >= 0 && dens < 32)) {

                        // Some block was set...
                        if (firstBlockHeight == -1) {
                            firstBlockHeight = y;
                        }

                        if (calcCaveDensity(chunk.chunkToWorldPositionX(x), yPos, chunk.chunkToWorldPositionZ(z)) > -0.7) {
                            generateOuterLayer(x, y, z, firstBlockHeight, chunk, type);
                        } else {
                            chunk.setBlock(x, y, z, air);
                        }

                        continue;
                    } else if (dens >= 32) {

                        // Some block was set...
                        if (firstBlockHeight == -1) {
                            firstBlockHeight = y;
                        }

                        if (calcCaveDensity(chunk.chunkToWorldPositionX(x), yPos, chunk.chunkToWorldPositionZ(z)) > -0.6) {
                            generateInnerLayer(x, y, z, chunk, type);
                        } else {
                            chunk.setBlock(x, y, z, air);
                        }

                        continue;
                    }

                    // Nothing was set!
                    firstBlockHeight = -1;
                }
            }
        }
    }

    private void generateInnerLayer(int x, int y, int z, CoreChunk c, WorldBiomeProvider.Biome type) {
        // TODO: GENERATE MINERALS HERE - config waiting at org\terasology\logic\manager\DefaultConfig.groovy 2012/01/22
        c.setBlock(x, y, z, stone);
    }

    private void generateOuterLayer(int x, int y, int z, int firstBlockHeight, CoreChunk c, WorldBiomeProvider.Biome type) {

        int depth = (firstBlockHeight - y);
        int yPos = c.chunkToWorldPositionY(y);

        switch (type) {
            case FOREST:
            case PLAINS:
            case MOUNTAINS:
                // Beach
                if (yPos >= 28 && yPos <= 34) {
                    c.setBlock(x, y, z, sand);
                } else if (depth == 0 && yPos > 32 && yPos < 128) {
                    // Grass on top
                    c.setBlock(x, y, z, grass);
                } else if (depth == 0 && yPos >= 128) {
                    // Snow on top
                    c.setBlock(x, y, z, snow);
                } else if (depth > 32) {
                    // Stone
                    c.setBlock(x, y, z, stone);
                } else {
                    // Dirt
                    c.setBlock(x, y, z, dirt);
                }

                break;
            case SNOW:
                if (depth == 0.0 && yPos > 32) {
                    // Snow on top
                    c.setBlock(x, y, z, snow);
                } else if (depth > 32) {
                    // Stone
                    c.setBlock(x, y, z, stone);
                } else {
                    // Dirt
                    c.setBlock(x, y, z, dirt);
                }

                break;

            case DESERT:
                if (depth > 8) {
                    // Stone
                    c.setBlock(x, y, z, stone);
                } else {
                    c.setBlock(x, y, z, sand);
                }

                break;
        }
    }

    private void triLerpDensityMap(double[][][] densityMap) {
        for (int x = 0; x < ChunkConstants.SIZE_X; x++) {
            for (int y = 0; y < ChunkConstants.SIZE_Y; y++) {
                for (int z = 0; z < ChunkConstants.SIZE_Z; z++) {
                    if (!(x % SAMPLE_RATE_3D_HOR == 0 && y % SAMPLE_RATE_3D_VERT == 0 && z % SAMPLE_RATE_3D_HOR == 0)) {
                        int offsetX = (x / SAMPLE_RATE_3D_HOR) * SAMPLE_RATE_3D_HOR;
                        int offsetY = (y / SAMPLE_RATE_3D_VERT) * SAMPLE_RATE_3D_VERT;
                        int offsetZ = (z / SAMPLE_RATE_3D_HOR) * SAMPLE_RATE_3D_HOR;
                        densityMap[x][y][z] = TeraMath.triLerp(x, y, z,
                                densityMap[offsetX][offsetY][offsetZ],
                                densityMap[offsetX][SAMPLE_RATE_3D_VERT + offsetY][offsetZ],
                                densityMap[offsetX][offsetY][offsetZ + SAMPLE_RATE_3D_HOR],
                                densityMap[offsetX][offsetY + SAMPLE_RATE_3D_VERT][offsetZ + SAMPLE_RATE_3D_HOR],
                                densityMap[SAMPLE_RATE_3D_HOR + offsetX][offsetY][offsetZ],
                                densityMap[SAMPLE_RATE_3D_HOR + offsetX][offsetY + SAMPLE_RATE_3D_VERT][offsetZ],
                                densityMap[SAMPLE_RATE_3D_HOR + offsetX][offsetY][offsetZ + SAMPLE_RATE_3D_HOR],
                                densityMap[SAMPLE_RATE_3D_HOR + offsetX][offsetY + SAMPLE_RATE_3D_VERT][offsetZ + SAMPLE_RATE_3D_HOR],
                                offsetX, SAMPLE_RATE_3D_HOR + offsetX, offsetY, SAMPLE_RATE_3D_VERT + offsetY, offsetZ, offsetZ + SAMPLE_RATE_3D_HOR);
                    }
                }
            }
        }
    }

    public double calcDensity(int x, int y, int z) {
        double height = calcBaseTerrain(x, z);
        double ocean = calcOceanTerrain(x, z);
        double river = calcRiverTerrain(x, z);

        float temp = biomeProvider.getTemperatureAt(x, z);
        float humidity = biomeProvider.getHumidityAt(x, z) * temp;

        Vector2f distanceToMountainBiome = new Vector2f(temp - 0.25f, humidity - 0.35f);

        double mIntens = TeraMath.clamp(1.0 - distanceToMountainBiome.length() * 3.0);
        double densityMountains = calcMountainDensity(x, y, z) * mIntens;
        double densityHills = calcHillDensity(x, y, z) * (1.0 - mIntens);

        return -y + (((32.0 + height * 32.0) * TeraMath.clamp(river + 0.25) * TeraMath.clamp(ocean + 0.25)) + densityMountains * 1024.0 + densityHills * 128.0);
    }

    private double calcBaseTerrain(float x, float z) {
        return TeraMath.clamp((pGen1.noise(0.004f * x, 0, 0.004f * z) + 1.0) / 2.0);
    }

    private double calcOceanTerrain(float x, float z) {
        return TeraMath.clamp(pGen2.noise(0.0009f * x, 0, 0.0009f * z) * 8.0);
    }

    private double calcRiverTerrain(float x, float z) {
        return TeraMath.clamp((java.lang.Math.sqrt(java.lang.Math.abs(pGen3.noise(0.0008f * x, 0, 0.0008f * z))) - 0.1) * 7.0);
    }

    private float calcMountainDensity(float x, float y, float z) {
        float x1 = x * 0.002f;
        float y1 = y * 0.001f;
        float z1 = z * 0.002f;

        float result = pGen4.noise(x1, y1, z1);
        return result > 0.0 ? result : 0;
    }

    private float calcHillDensity(float x, float y, float z) {
        float x1 = x * 0.008f;
        float y1 = y * 0.006f;
        float z1 = z * 0.008f;

        float result = pGen5.noise(x1, y1, z1) - 0.1f;
        return result > 0.0 ? result : 0;
    }

    private float calcCaveDensity(float x, float y, float z) {
        return pGen8.noise(x * 0.02f, y * 0.02f, z * 0.02f);
    }

    @Override
    public Map<String, String> getInitParameters() {
        return null;
    }

    @Override
    public void setInitParameters(final Map<String, String> initParameters) {
    }

}
