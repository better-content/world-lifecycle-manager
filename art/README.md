# World Condenser textures

The PNGs in `source-textures/` are the 1254x1254 generated masters. Rebuild the
Minecraft-resolution textures from the repository root with:

```sh
java tools/DownsampleTextures.java
```

The tool progressively downsamples each master to 16x16, restores enough local
contrast and saturation for block-scale readability, quantizes the result, and
writes nearest-neighbor inspection previews under the ignored
`build/texture-previews/` directory.
