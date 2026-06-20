# Roadmap — scrap-minecraft-to-blender

Ideias para transformar o Recorte numa ponte completa **Minecraft → Blender**, focada em
**animação, VFX e cinema**. Marcado com ✅ o que já existe.

## ✅ Já funciona
- Player (corpo, skin, 2ª camada, **armadura + Curios/Artifacts**) com esqueleto.
- Mobs com ossos (vanilla) + fallback de captura (GeckoLib).
- Itens, blocos e **mod inteiro** em lote.
- **Cena** (diorama do mundo) e **Snapshot** (cena + mobs rigados) com culling, tint e emissão.
- glTF skinned multi-objeto + OBJ, texturas por sprite, cor de vértice.

## 🦴 Animação
- **Exportar ciclos de animação** (idle/andar/atacar) como animação glTF: amostrar `setupAnim` ao longo
  do tempo e gravar keyframes por osso → o modelo **se mexe** no Blender.
- **Gravar uma animação ao vivo**: capturar a pose de uma entidade por N ticks reais → qualquer coisa
  que o mob fizer no jogo vira animação bakeada.
- **Animações de block entity** (baú abrindo, portão, conduit, beacon).
- **Animação do item na mão / 1ª pessoa** (swing, uso, bloqueio).
- Suporte a **GeckoLib animations** (ler os `.animation.json`).

## ✨ VFX / Partículas
- **Exportar partículas** (fogo, fumaça, portal, redstone, poções, totem) como *point cloud* / malha
  instanciada com posição+cor → Geometry/Particle Nodes no Blender.
- **Capturar um efeito ao longo do tempo** → VFX animado (rastro de partículas).
- **Beacon beam, end gateway, dragon breath, explosões**.
- **Superfície de fluidos** (água/lava) com normal/flow e textura animada.

## 💡 Iluminação & ambiente
- Exportar **cor de céu/fog/bioma + ângulo do sol** → recriar a ambientação no Blender (sun + world).
- **Block light / sky light** bakeado (vertex color ou light probes) para casar com a iluminação do jogo.
- Skybox, nuvens, estrelas.
- **Câmera**: exportar posição/FOV do player → uma câmera do Blender igual à sua visão.

## 🧱 Mais coisas pra extrair
- **Regiões maiores / schematics** (NBT structures, `.litematic`).
- **Placas (texto), quadros, item frames, mapas** como malhas/planos texturizados.
- **Texturas animadas** (água, lava, fogo, portal) exportadas como sequência de imagens.
- **PBR de resource packs** (LabPBR: normal/specular) e *connected textures*.
- **Transparência real** (vidro, água) em modo BLEND, não só MASK.

## 🎬 Cinema / cena completa
- **Export "cinematic"**: cena + entidades **rigadas** + câmera + luz + céu → uma cena Blender pronta
  pra renderizar de um momento do jogo.
- **Timeline**: capturar uma sequência de frames (mobs andando, água correndo) → animação completa.
- Integração com mods de **replay** para exportar trechos gravados.

## 🧰 Workflow & conforto
- **Addon de Blender** que importa o export sozinho: monta materiais (nearest, emissão, alpha), faz
  *parent* no esqueleto e arruma a escala — 1 clique.
- **Tela in-game** (GUI) com **preview** pra escolher o que exportar e as opções (raio, interiores,
  detalhe), em vez de digitar comando.
- **Presets** (low/high detail, com/sem interiores, com/sem entidades).
- Exportar **vários mobs do mod** (entidades em lote, hoje só itens/blocos).
- Botão **"abrir pasta"** após exportar.

## 🏗️ Qualidade técnica
- **Atlas compartilhado** no lote de mod (hoje cada item duplica seu sprite).
- **Merge por material** entre objetos para reduzir draw calls no Blender.
- Opção de **bind pose / T-pose** vs pose atual.
- Bones de mob mais limpos (nomear pelas partes mesmo no jogo ofuscado).

---

Sugestões? Abra uma issue. 🙌
