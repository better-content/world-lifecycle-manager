package com.bettercontent.worldlifecyclemanager;

import com.simibubi.create.api.schematic.nbt.PartialSafeNBT;
import com.simibubi.create.api.schematic.nbt.SafeNbtWriterRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

public final class SchematicLibrary {
    public static final long MAX_BYTES = 256_000L;
    public static final long MAX_DECOMPRESSED_BYTES = 64L * 1024L * 1024L;
    public static final int MAX_PUBLISHED = 256;
    public static final int MAX_DIRECTORY_SCAN = 512;
    private static final String ENTRY_MAGIC = "BC_PRESTIGE_SCHEMATIC_V2";

    public record Entry(String id, String sha256, String author, String originalName, long size, long generation) {}

    private SchematicLibrary() {}

    public static Entry publish(MinecraftServer server, String author, String fileName,
                                byte[] compressedNbt, long generation) throws IOException {
        return publish(serverRoot(server), author, fileName, compressedNbt, generation, server);
    }

    static Entry publish(Path serverRoot, String author, String fileName,
                         byte[] compressedNbt, long generation) throws IOException {
        return publish(serverRoot, author, fileName, compressedNbt, generation, null);
    }

    private static Entry publish(Path serverRoot, String author, String fileName,
                                 byte[] compressedNbt, long generation,
                                 MinecraftServer server) throws IOException {
        PrestigeContracts.validateAuthor(author);
        if (!safeFileName(fileName)) throw new IllegalArgumentException("unsafe schematic filename");
        if (generation < 0) throw new IllegalArgumentException("schematic generation is negative");
        if (compressedNbt == null || compressedNbt.length < 2 || compressedNbt.length > MAX_BYTES) {
            throw new IllegalArgumentException("schematic is outside the size limit");
        }
        if ((compressedNbt[0] & 0xff) != 0x1f || (compressedNbt[1] & 0xff) != 0x8b) {
            throw new IllegalArgumentException("schematic is not gzip encoded");
        }
        byte[] sanitized = sanitize(compressedNbt, server);
        long size = sanitized.length;
        String digest = sha256(sanitized);
        String id = digest.substring(0, 16) + "-" + author.toLowerCase(Locale.ROOT);
        Path library = libraryRoot(serverRoot);
        Path objects = library.resolve("objects");
        Path entries = library.resolve("entries");
        Files.createDirectories(objects);
        Files.createDirectories(entries);
        Path entryPath = entries.resolve(id + ".tsv");
        if (!Files.isRegularFile(entryPath, LinkOption.NOFOLLOW_LINKS)
                && countPublishedEntryFiles(entries) >= MAX_PUBLISHED) {
            throw new IllegalStateException("published schematic catalog is full (" + MAX_PUBLISHED + ")");
        }
        Path object = objects.resolve(digest + ".nbt");
        if (Files.exists(object) && (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(object) || Files.size(object) != size || !sha256(object).equals(digest))) {
            throw new IOException("existing content-addressed schematic object failed integrity verification");
        }
        if (!Files.exists(object)) {
            Path partial = object.resolveSibling(object.getFileName() + ".partial");
            if (Files.exists(partial)) {
                if (Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(partial)
                        && Files.size(partial) == size && sha256(partial).equals(digest)) {
                    atomicMove(partial, object);
                } else {
                    Files.delete(partial);
                }
            }
            if (!Files.exists(object)) Files.write(partial, sanitized, StandardOpenOption.CREATE_NEW);
            if (Files.exists(object)) {
                // A valid interrupted publication was promoted above.
            } else if (!sha256(partial).equals(digest)) {
                Files.deleteIfExists(partial);
                throw new IOException("schematic changed while publishing");
            } else {
                atomicMove(partial, object);
            }
        }
        Entry entry = new Entry(id, digest, author, fileName, size, generation);
        writeEntry(entryPath, entry);
        return entry;
    }

    static byte[] sanitize(byte[] source, MinecraftServer server) throws IOException {
        CompoundTag input;
        try (InputStream raw = new ByteArrayInputStream(source);
             DataInputStream data = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            input = NbtIo.read(data, new NbtAccounter(MAX_DECOMPRESSED_BYTES));
        } catch (Exception error) {
            throw new IllegalArgumentException("schematic NBT is unreadable", error);
        }
        if (input == null || !input.contains("size", Tag.TAG_LIST)
                || !input.contains("palette", Tag.TAG_LIST) || !input.contains("blocks", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("schematic is not a StructureTemplate payload");
        }
        ListTag size = input.getList("size", Tag.TAG_INT);
        if (size.size() != 3) throw new IllegalArgumentException("schematic size must contain three integers");
        int sizeX = size.getInt(0), sizeY = size.getInt(1), sizeZ = size.getInt(2);
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1 || sizeX > 2048 || sizeY > 2048 || sizeZ > 2048) {
            throw new IllegalArgumentException("schematic dimensions are outside the supported range");
        }
        ListTag palette = input.getList("palette", Tag.TAG_COMPOUND);
        if (palette.isEmpty()) throw new IllegalArgumentException("schematic palette is empty");
        ListTag sanitizedPalette = new ListTag();
        List<BlockState> states = new ArrayList<>(palette.size());
        var blocks = server == null ? null : server.registryAccess().registryOrThrow(Registries.BLOCK);
        for (int index = 0; index < palette.size(); index++) {
            CompoundTag encoded = palette.getCompound(index);
            if (!encoded.contains("Name", Tag.TAG_STRING)) {
                throw new IllegalArgumentException("schematic palette row lacks a block name");
            }
            ResourceLocation id = ResourceLocation.tryParse(encoded.getString("Name"));
            if (id == null || (blocks != null && !blocks.containsKey(id))) {
                throw new IllegalArgumentException("schematic palette contains an unregistered block");
            }
            if (blocks != null) {
                BlockState state = NbtUtils.readBlockState(blocks.asLookup(), encoded);
                states.add(state);
                sanitizedPalette.add(NbtUtils.writeBlockState(state));
                continue;
            }
            CompoundTag clean = new CompoundTag();
            clean.putString("Name", id.toString());
            if (encoded.contains("Properties", Tag.TAG_COMPOUND)) {
                CompoundTag sourceProperties = encoded.getCompound("Properties");
                CompoundTag cleanProperties = new CompoundTag();
                for (String key : sourceProperties.getAllKeys()) {
                    if (!sourceProperties.contains(key, Tag.TAG_STRING)) {
                        throw new IllegalArgumentException("schematic block-state property is not a string");
                    }
                    cleanProperties.putString(key, sourceProperties.getString(key));
                }
                clean.put("Properties", cleanProperties);
            }
            sanitizedPalette.add(clean);
        }

        ListTag sanitizedBlocks = new ListTag();
        Set<BlockPos> occupied = new HashSet<>();
        ListTag sourceBlocks = input.getList("blocks", Tag.TAG_COMPOUND);
        for (int index = 0; index < sourceBlocks.size(); index++) {
            CompoundTag sourceBlock = sourceBlocks.getCompound(index);
            if (!sourceBlock.contains("pos", Tag.TAG_LIST) || !sourceBlock.contains("state", Tag.TAG_INT)) {
                throw new IllegalArgumentException("schematic block row is malformed");
            }
            ListTag position = sourceBlock.getList("pos", Tag.TAG_INT);
            if (position.size() != 3) throw new IllegalArgumentException("schematic block position is malformed");
            BlockPos pos = new BlockPos(position.getInt(0), position.getInt(1), position.getInt(2));
            if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0
                    || pos.getX() >= sizeX || pos.getY() >= sizeY || pos.getZ() >= sizeZ
                    || !occupied.add(pos)) {
                throw new IllegalArgumentException("schematic block position is outside the structure or duplicated");
            }
            int stateIndex = sourceBlock.getInt("state");
            if (stateIndex < 0 || stateIndex >= palette.size()) {
                throw new IllegalArgumentException("schematic block state index is outside the palette");
            }
            CompoundTag outputBlock = new CompoundTag();
            outputBlock.put("pos", position.copy());
            outputBlock.putInt("state", stateIndex);
            if (server != null && sourceBlock.contains("nbt", Tag.TAG_COMPOUND)) {
                CompoundTag safe = safeBlockEntityNbt(server, pos, states.get(stateIndex), sourceBlock.getCompound("nbt"));
                if (safe != null) outputBlock.put("nbt", safe);
            }
            sanitizedBlocks.add(outputBlock);
        }

        CompoundTag output = new CompoundTag();
        if (input.contains("DataVersion", Tag.TAG_INT)) output.putInt("DataVersion", input.getInt("DataVersion"));
        output.put("size", size.copy());
        output.put("palette", sanitizedPalette);
        output.put("blocks", sanitizedBlocks);
        output.put("entities", new ListTag());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(output, bytes);
        byte[] sanitized = bytes.toByteArray();
        if (sanitized.length < 2 || sanitized.length > MAX_BYTES) {
            throw new IllegalArgumentException("sanitized schematic is outside the size limit");
        }
        return sanitized;
    }

    static CompoundTag safeBlockEntityNbt(MinecraftServer server, BlockPos pos, BlockState state,
                                          CompoundTag source) {
        if (!state.hasBlockEntity() || !(state.getBlock() instanceof EntityBlock entityBlock)) return null;
        BlockEntity entity = entityBlock.newBlockEntity(pos, state);
        if (entity == null) throw new IllegalArgumentException("schematic block entity could not be constructed");
        var registeredWriter = SafeNbtWriterRegistry.REGISTRY.get(entity.getType());
        if (!(entity instanceof PartialSafeNBT) && registeredWriter == null) return null;
        try {
            entity.setLevel(server.overworld());
            entity.load(source.copy());
            CompoundTag safe = new CompoundTag();
            if (entity instanceof PartialSafeNBT partial) partial.writeSafe(safe);
            if (registeredWriter != null) registeredWriter.writeSafe(entity, safe);
            ResourceLocation type = server.registryAccess().registryOrThrow(Registries.BLOCK_ENTITY_TYPE).getKey(entity.getType());
            if (type == null) throw new IllegalArgumentException("schematic block entity type is unregistered");
            safe.putString("id", type.toString());
            safe.putInt("x", pos.getX()); safe.putInt("y", pos.getY()); safe.putInt("z", pos.getZ());
            return safe;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("schematic block entity NBT could not be sanitized", error);
        }
    }

    public static List<Entry> list(MinecraftServer server) throws IOException {
        return list(serverRoot(server));
    }

    static List<Entry> list(Path serverRoot) throws IOException {
        Path entries = libraryRoot(serverRoot).resolve("entries");
        if (!Files.isDirectory(entries, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Path> entryPaths = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(entries)) {
            int scanned = 0;
            for (Path path : paths) {
                if (++scanned > MAX_DIRECTORY_SCAN) throw new IllegalStateException(
                        "published schematic directory exceeds " + MAX_DIRECTORY_SCAN + " entries");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                    if (entryPaths.size() >= MAX_PUBLISHED) throw new IllegalStateException(
                            "published schematic catalog exceeds " + MAX_PUBLISHED + " entries");
                    entryPaths.add(path);
                }
            }
        }
        entryPaths.sort(Comparator.comparing(path -> path.getFileName().toString()));
        List<Entry> result = new ArrayList<>();
        for (Path path : entryPaths) result.add(readEntry(path));
        return List.copyOf(result);
    }

    private static int countPublishedEntryFiles(Path entries) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(entries)) {
            int scanned = 0;
            for (Path path : paths) {
                if (++scanned > MAX_DIRECTORY_SCAN) throw new IllegalStateException(
                        "published schematic directory exceeds " + MAX_DIRECTORY_SCAN + " entries");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) count++;
            }
        }
        return count;
    }

    public static byte[] download(MinecraftServer server, String id) throws IOException {
        return download(serverRoot(server), id);
    }

    static byte[] download(Path serverRoot, String id) throws IOException {
        PrestigeContracts.validateId("schematic entry ID", id);
        Path entryPath = libraryRoot(serverRoot).resolve("entries").resolve(id + ".tsv").normalize();
        Entry entry = readEntry(entryPath);
        Path object = libraryRoot(serverRoot).resolve("objects").resolve(entry.sha256() + ".nbt").normalize();
        if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(object)
                || Files.size(object) != entry.size() || !sha256(object).equals(entry.sha256())) {
            throw new IOException("published schematic object failed integrity verification");
        }
        return Files.readAllBytes(object);
    }

    public static void remove(MinecraftServer server, String id) throws IOException {
        remove(serverRoot(server), id);
    }

    static void remove(Path serverRoot, String id) throws IOException {
        PrestigeContracts.validateId("schematic entry ID", id);
        Path entries = libraryRoot(serverRoot).resolve("entries").normalize();
        Path target = entries.resolve(id + ".tsv").normalize();
        if (!target.startsWith(entries)) throw new IllegalArgumentException("entry path escaped catalog");
        Files.deleteIfExists(target);
    }

    private static void writeEntry(Path path, Entry entry) throws IOException {
        String encodedName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(entry.originalName().getBytes(StandardCharsets.UTF_8));
        String payload = String.join("\n", ENTRY_MAGIC, "id\t" + entry.id(), "sha256\t" + entry.sha256(),
                "author\t" + entry.author(), "name_b64\t" + encodedName, "size\t" + entry.size(),
                "generation\t" + entry.generation()) + "\n";
        Files.createDirectories(path.getParent());
        Path partial = path.resolveSibling(path.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        Files.writeString(partial, payload, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        atomicMove(partial, path);
    }

    private static Entry readEntry(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != 7 || !lines.get(0).equals(ENTRY_MAGIC)) throw new IllegalArgumentException("invalid schematic entry");
        String id = field(lines.get(1), "id");
        String digest = field(lines.get(2), "sha256");
        String author = field(lines.get(3), "author");
        String encodedName = field(lines.get(4), "name_b64");
        String name = new String(Base64.getUrlDecoder().decode(encodedName), StandardCharsets.UTF_8);
        long size = Long.parseLong(field(lines.get(5), "size"));
        long generation = Long.parseLong(field(lines.get(6), "generation"));
        PrestigeContracts.validateId("schematic entry ID", id);
        PrestigeContracts.validateAuthor(author);
        if (!digest.matches("[0-9a-f]{64}") || !safeFileName(name) || size < 2 || size > MAX_BYTES || generation < 0) {
            throw new IllegalArgumentException("invalid schematic entry fields");
        }
        return new Entry(id, digest, author, name, size, generation);
    }

    private static String field(String line, String key) {
        String prefix = key + "\t";
        if (!line.startsWith(prefix) || line.indexOf('\t', prefix.length()) >= 0) throw new IllegalArgumentException("invalid " + key + " field");
        return line.substring(prefix.length());
    }

    static boolean safeFileName(String name) {
        return name != null && name.matches("[A-Za-z0-9._ -]{1,120}\\.nbt") && !name.equals(".nbt")
                && !name.startsWith(".") && !name.contains("..") && !name.contains("/") && !name.contains("\\");
    }

    private static Path serverRoot(MinecraftServer server) {
        return server.getServerDirectory().toPath().toAbsolutePath().normalize();
    }

    private static Path libraryRoot(Path serverRoot) { return serverRoot.resolve(".world_lifecycle_manager/schematics").normalize(); }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.deleteIfExists(source);
            throw new IOException("schematic filesystem does not support atomic publication", error);
        }
    }
}
