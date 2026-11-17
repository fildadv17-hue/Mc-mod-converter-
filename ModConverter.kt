package com.example.modconverter

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ConversionStats(
    val modId: String,
    val itemTextures: Int,
    val blockTextures: Int,
    val generatedItems: Int,
    val generatedBlocks: Int
)

object ModConverter {

    fun convertJarToMcAddon(
        jarInputStream: InputStream,
        outputMcAddonFile: File,
        addonName: String,
        logger: (String) -> Unit
    ): ConversionStats {
        var modId = "custommod"
        val itemTextures = mutableMapOf<String, ByteArray>()
        val blockTextures = mutableMapOf<String, ByteArray>()
        val langEntries = mutableMapOf<String, String>()

        val regexItemTex = Regex("assets/([^/]+)/textures/item(?:s)?/([^/]+)\.png")
        val regexBlockTex = Regex("assets/([^/]+)/textures/block(?:s)?/([^/]+)\.png")
        val regexLang = Regex("assets/([^/]+)/lang/en_us\.json")

        logger("Scanning .jar archive entries...")
        ZipInputStream(jarInputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    regexItemTex.matchEntire(name)?.let { match ->
                        modId = match.groupValues[1]
                        val texName = match.groupValues[2]
                        itemTextures[texName] = readBytes(zis)
                        logger("Found item texture: $texName.png")
                    }
                    regexBlockTex.matchEntire(name)?.let { match ->
                        modId = match.groupValues[1]
                        val texName = match.groupValues[2]
                        blockTextures[texName] = readBytes(zis)
                        logger("Found block texture: $texName.png")
                    }
                    regexLang.matchEntire(name)?.let {
                        try {
                            val jsonStr = String(readBytes(zis), Charsets.UTF_8)
                            val json = JSONObject(jsonStr)
                            json.keys().forEach { k ->
                                langEntries[k] = json.optString(k)
                            }
                            logger("Parsed en_us.json language file (${langEntries.size} keys)")
                        } catch (e: Exception) {
                            logger("Skipped malformed lang file: ${e.message}")
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val rpUuidHeader = UUID.randomUUID().toString()
        val rpUuidModule = UUID.randomUUID().toString()
        val bpUuidHeader = UUID.randomUUID().toString()
        val bpUuidModule = UUID.randomUUID().toString()

        logger("Building Bedrock Resource Pack (RP) & Behavior Pack (BP)...")

        ZipOutputStream(FileOutputStream(outputMcAddonFile)).use { zos ->
            val rpManifest = createManifest(
                name = "$addonName RP",
                description = "Converted Resource Pack from Java mod ($modId)",
                headerUuid = rpUuidHeader,
                moduleUuid = rpUuidModule,
                moduleType = "resources",
                dependencyUuid = null
            )
            writeZipText(zos, "${addonName}_RP/manifest.json", rpManifest.toString(2))

            val bpManifest = createManifest(
                name = "$addonName BP",
                description = "Converted Behavior Pack from Java mod ($modId)",
                headerUuid = bpUuidHeader,
                moduleUuid = bpUuidModule,
                moduleType = "data",
                dependencyUuid = rpUuidHeader
            )
            writeZipText(zos, "${addonName}_BP/manifest.json", bpManifest.toString(2))

            val itemTextureData = JSONObject()
            for ((itemName, bytes) in itemTextures) {
                val cleanName = itemName.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                val texPath = "textures/items/$cleanName"
                writeZipBytes(zos, "${addonName}_RP/$texPath.png", bytes)

                val texEntry = JSONObject().put("textures", texPath)
                itemTextureData.put("${modId}_$cleanName", texEntry)

                val itemBpJson = createBedrockItemJson(modId, cleanName, "${modId}_$cleanName")
                writeZipText(zos, "${addonName}_BP/items/$cleanName.json", itemBpJson.toString(2))
            }

            val itemTextureJson = JSONObject()
                .put("resource_pack_name", addonName)
                .put("texture_name", "atlas.items")
                .put("texture_data", itemTextureData)
            writeZipText(zos, "${addonName}_RP/textures/item_texture.json", itemTextureJson.toString(2))

            val terrainTextureData = JSONObject()
            for ((blockName, bytes) in blockTextures) {
                val cleanName = blockName.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                val texPath = "textures/blocks/$cleanName"
                writeZipBytes(zos, "${addonName}_RP/$texPath.png", bytes)

                val texEntry = JSONObject().put("textures", texPath)
                terrainTextureData.put("${modId}_$cleanName", texEntry)

                val blockBpJson = createBedrockBlockJson(modId, cleanName, "${modId}_$cleanName")
                writeZipText(zos, "${addonName}_BP/blocks/$cleanName.json", blockBpJson.toString(2))
            }

            val terrainTextureJson = JSONObject()
                .put("resource_pack_name", addonName)
                .put("texture_name", "atlas.terrain")
                .put("padding", 8)
                .put("num_mip_levels", 4)
                .put("texture_data", terrainTextureData)
            writeZipText(zos, "${addonName}_RP/textures/terrain_texture.json", terrainTextureJson.toString(2))

            val langBuilder = StringBuilder()
            for (itemName in itemTextures.keys) {
                val cleanName = itemName.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                val displayName = langEntries["item.$modId.$itemName"]
                    ?: cleanName.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                langBuilder.append("item.$modId:$cleanName.name=$displayName
")
            }
            for (blockName in blockTextures.keys) {
                val cleanName = blockName.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                val displayName = langEntries["block.$modId.$blockName"]
                    ?: cleanName.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                langBuilder.append("tile.$modId:$cleanName.name=$displayName
")
            }
            writeZipText(zos, "${addonName}_RP/texts/en_US.lang", langBuilder.toString())
        }

        return ConversionStats(
            modId = modId,
            itemTextures = itemTextures.size,
            blockTextures = blockTextures.size,
            generatedItems = itemTextures.size,
            generatedBlocks = blockTextures.size
        )
    }

    private fun createManifest(
        name: String,
        description: String,
        headerUuid: String,
        moduleUuid: String,
        moduleType: String,
        dependencyUuid: String?
    ): JSONObject {
        val minEngine = JSONArray().put(1).put(20).put(80)
        val versionArr = JSONArray().put(1).put(0).put(0)

        val header = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("uuid", headerUuid)
            .put("version", versionArr)
            .put("min_engine_version", minEngine)

        val module = JSONObject()
            .put("type", moduleType)
            .put("uuid", moduleUuid)
            .put("version", versionArr)

        val manifest = JSONObject()
            .put("format_version", 2)
            .put("header", header)
            .put("modules", JSONArray().put(module))

        if (dependencyUuid != null) {
            val dep = JSONObject()
                .put("uuid", dependencyUuid)
                .put("version", versionArr)
            manifest.put("dependencies", JSONArray().put(dep))
        }
        return manifest
    }

    private fun createBedrockItemJson(modId: String, itemName: String, iconAtlasKey: String): JSONObject {
        val components = JSONObject()
            .put("minecraft:icon", JSONObject().put("texture", iconAtlasKey))
            .put("minecraft:display_name", JSONObject().put("value", "item.$modId:$itemName.name"))

        val description = JSONObject()
            .put("identifier", "$modId:$itemName")
            .put("menu_category", JSONObject().put("category", "items"))

        val itemObj = JSONObject()
            .put("description", description)
            .put("components", components)

        return JSONObject()
            .put("format_version", "1.20.80")
            .put("minecraft:item", itemObj)
    }

    private fun createBedrockBlockJson(modId: String, blockName: String, textureAtlasKey: String): JSONObject {
        val materialInstances = JSONObject().put(
            "*",
            JSONObject()
                .put("texture", textureAtlasKey)
                .put("render_method", "opaque")
        )
        val components = JSONObject()
            .put("minecraft:geometry", "minecraft:geometry.full_block")
            .put("minecraft:material_instances", materialInstances)

        val description = JSONObject()
            .put("identifier", "$modId:$blockName")
            .put("menu_category", JSONObject().put("category", "construction"))

        val blockObj = JSONObject()
            .put("description", description)
            .put("components", components)

        return JSONObject()
            .put("format_version", "1.20.80")
            .put("minecraft:block", blockObj)
    }

    private fun readBytes(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(4096)
        var nRead: Int
        while (input.read(data, 0, data.size).also { nRead = it } != -1) {
            buffer.write(data, 0, nRead)
        }
        return buffer.toByteArray()
    }

    private fun writeZipText(zos: ZipOutputStream, path: String, content: String) {
        writeZipBytes(zos, path, content.toByteArray(Charsets.UTF_8))
    }

    private fun writeZipBytes(zos: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path)
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }
}
