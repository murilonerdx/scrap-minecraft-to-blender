# scrap-minecraft-to-blender

> **Recorte** — exporte (quase) qualquer coisa do Minecraft direto pro **Blender**: seu personagem,
> mobs, itens, blocos, mods inteiros e até **cenas do mundo** — com **esqueleto (ossos)**, texturas,
> emissão e tints, em **glTF (`.glb`)** e **OBJ**.

Mod **client-side** para **Minecraft 1.20.1 / Forge**. Você aperta uma tecla (ou usa um comando) dentro
do jogo e os arquivos aparecem prontos pra importar no Blender.

---

## ✨ O que dá pra exportar

| Comando | O que faz | Ossos |
|---|---|:---:|
| `O` (tecla) ou `/recorte export` | o player/mob que você está **olhando**, ou você | ✅ |
| `/recorte export player <nome>` | um player pelo nome | ✅ |
| `/recorte export entity <id>` | um **mob** (ex.: `minecraft:zombie`) | ✅ vanilla¹ |
| `/recorte export item <id>` | modelo 3D de um **item** (espada, ovo…) | – |
| `/recorte export block <id>` | modelo de um **bloco** | – |
| `/recorte export mod <modid>` | **todos** os itens + blocos de um mod (lote) | – |
| `/recorte export scene [raio]` | 🎬 **cenário** ao redor (diorama do seu build/terreno) | – |
| `/recorte export snapshot [raio]` | 🎬 **o momento**: cena + todos os mobs por perto (mobs **rigados**) | ✅ |

¹ Mobs vanilla (`HumanoidModel`/`HierarchicalModel`) saem **com ossos**. Mobs de **GeckoLib** caem
para uma captura estática (mas saem!).

**Extras automáticos:**
- 🦴 **Esqueleto/armature** pronto pra animar (player e mobs).
- 🎨 **Texturas** recortadas por sprite (pequenas, não o atlas inteiro).
- 💡 **Emissão** — lava, glowstone, tochas e lanternas **brilham** no Blender.
- 🌿 **Tints** de bioma (grama/folha/água) como **cor de vértice**.
- 🧱 **Culling** das faces escondidas nas cenas (não exporta interiores).
- 👕 Armadura, item na mão e **acessórios Curios/Artifacts** do player (objeto `Accessories` separado).

---

## 🚀 Instalação

1. Tenha **Minecraft 1.20.1** com **Forge 47.x**.
2. Baixe/gere o `recorte-0.1.0.jar` (veja **Build** abaixo) e jogue na pasta `mods` da sua instância.
3. Pronto. O mod é só-cliente; pode usar em mundo single-player ou em servidores.

## 🔨 Build (a partir do código)

Requisitos: nada além do repositório — o Gradle Wrapper baixa tudo (inclusive um **JDK 17** via toolchain).

```bash
# Windows
.\gradlew.bat build
# Linux/Mac
./gradlew build
# Resultado: build/libs/recorte-0.1.0.jar
```

Rodar um cliente de teste (dev):

```bash
./gradlew runClient
```

> Obs.: mods com mixins (Oculus/Embeddium) **não** carregam no `runClient` de dev por diferença de
> mapeamento. Para testar junto com esses mods, instale o `.jar` na sua instância real.

---

## 🎮 Como usar

1. Entre num mundo.
2. Aperte **`O`** (rebindável em *Opções → Controles → Recorte*) ou use um `/recorte export …`.
3. Os arquivos saem em:
   ```
   <pasta da instância>/recorte_exports/<data_hora>_<nome>/
     ├── <nome>.glb     ← glTF (melhor): ossos + texturas embutidas
     ├── <nome>.obj/.mtl
     └── *.png          ← texturas
   ```

### Exemplos
```
/recorte export entity minecraft:zombie
/recorte export item minecraft:diamond_sword
/recorte export block minecraft:furnace
/recorte export mod artifacts
/recorte export scene 12
/recorte export snapshot 16
```

## 🟦 Abrir no Blender

- **glTF (recomendado):** `Arquivo → Importar → glTF 2.0` e escolha o `.glb`. Vem com armature e
  materiais ligados.
- Para visual **pixel-art** sem borrão: no material, troque o filtro da textura para **Closest**.
- **Emissão** (lava/glowstone): aparece no `emissiveFactor`/`emissiveTexture` — visível no EEVEE/Cycles.
- **Tints** (grama/água): vêm como *Color Attribute* (`COLOR_0`), multiplicado na cor base.
- Escala: 1 unidade = 1 bloco.

---

## 🧠 Como funciona

```
com.recorte
├── Recorte / client/*        @Mod, keybind, comando /recorte export
└── export
    ├── Exporter              orquestra cada modo (player/entity/item/block/scene/snapshot/mod)
    ├── ModelExtractor        player + mobs → esqueleto (walk de ModelPart) com geometria
    ├── BakedModelExtractor   itens/blocos → geometria a partir do BakedModel + sprite
    ├── SceneExtractor        blocos do mundo → diorama (culling, tint, emissão)
    ├── LayerCapturer         captura o RENDER real (armadura, Curios, mobs GeckoLib)
    ├── Ir                    representação intermediária (ossos, materiais, malha, cor)
    ├── GltfWriter / ObjWriter  escrevem .glb (skinned, multi-objeto) e .obj/.mtl
    ├── TextureExporter       lê texturas da GPU → PNG
    └── Convert / ReflectUtil  conversão de eixos; reflexão por TIPO (sobrevive à ofuscação)
```

**Ideias-chave**
- **Reflexão por *tipo de campo*** (não por nome) → o mesmo código funciona no dev e no jogo ofuscado,
  sem *access transformer*.
- **Captura do render real** num `VertexConsumer` → pega armadura, Curios e GeckoLib sem conhecer cada
  sistema de render.
- **Conversão de eixos** num único lugar; geometria capturada já vem ÷16 do jogo (cuidado documentado no
  código).

---

## 🗺️ Roadmap

Animações, VFX/partículas, iluminação, addon de Blender e muito mais em **[ROADMAP.md](ROADMAP.md)**.

## 📄 Licença

[MIT](LICENSE).
