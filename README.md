# Vorticis 3D Studio

App Android que cria modelos 3D **a partir de uma ou várias fotos** ou de **instruções em português**, e exporta em **12 formatos**. Tudo roda **no próprio celular**, sem servidor e sem internet.

## Instalar no celular (sem computador)

1. No celular, abra **https://github.com/hellisonvidal/Vorticis-3D-Studio/releases/latest**
2. Toque em **Vorticis3DStudio.apk** para baixar.
3. Abra o arquivo e permita **"Instalar apps desconhecidos"** quando o Android pedir.

Cada `push` na branch `main` gera um APK novo automaticamente (GitHub Actions → aba *Actions* / *Releases*).
Todas as versões são assinadas com a mesma chave, então dá para atualizar sem desinstalar.

## O que ele faz

### 1. Foto → 3D (1 foto só)
1. Toque na **câmera** (ou galeria, ou "Compartilhar → Vorticis 3D Studio" na galeria).
2. O objeto é recortado do fundo pelo **ML Kit Subject Segmentation** (rede neural do Google rodando no aparelho). Se o módulo do ML Kit não estiver disponível, entra a segmentação própria do Vorticis (k-means das cores da borda + Otsu + morfologia).
3. A **Vorticis Mind analisa a silhueta** (simetria, preenchimento, cores) e escolhe a técnica:

| Modo | Técnica | Bom para |
|---|---|---|
| **Volume** | Inflagem por equação de Poisson (Δu = −2, h = √u: seção transversal circular, estilo *Teddy/Monster Mash*) + micro-relevo pela luminância | bonecos, animais, frutas, pelúcias |
| **Revolução** | Perfil por linha a partir do eixo de simetria, girado 360° | vasos, garrafas, copos, luminárias |
| **Extrusão** | Recorte com espessura e chanfro | logos, placas, chaveiros, símbolos |
| **Relevo** | Mapa de altura pela luminosidade | litofanias, quadros, medalhas |

A textura é a própria foto. As malhas geradas são **fechadas** (*watertight*), prontas para impressão 3D.

### 1b. Várias fotos → 3D (forma real, inclusive o verso)
Depois da primeira foto, toque em **"+ Ângulo"** e fotografe andando **para a sua direita** ao redor do objeto, sempre na **mesma distância e altura**. Você também pode escolher várias fotos de uma vez na galeria, ou compartilhar várias fotos com o app.

- **Recomendado:** 4 fotos (frente, lado, trás, outro lado) ou 8 fotos em volta (a cada 45°).
- **Mínimo:** 2 fotos (frente + lado).
- Técnica: **casco visual** (*shape-from-silhouette*). Cada silhueta esculpe o volume, e a superfície é extraída com *Surface Nets* sobre um campo de distância com sinal. O resultado é uma malha lisa e fechada. A textura é montada projetando as próprias fotos de volta no modelo (frente com a foto da frente, verso com a de trás).
- Opções na tela: "Ao redor (360°)", "Ao redor (sentido inverso)" (se você andou para a esquerda), "Frente / lado / trás" e "Só a 1ª foto". Por voz ou texto: *"fotos no sentido inverso"*.
- Limitação do método: cavidades que nenhuma silhueta mostra (o interior de uma caneca, por exemplo) ficam preenchidas.

### 2. Texto → 3D (a "IA própria")
A **Vorticis Mind** é um interpretador de linguagem natural (PT-BR e EN) que roda offline. Ela entende:

- **44 objetos**: mesa, cadeira, sofá, cama, estante, casa, prédio, torre, castelo, árvore, pinheiro, flor, cogumelo, vaso, copo, caneca, garrafa, luminária, carro, foguete, avião, barco, robô, boneco, espada, troféu, escada, coluna, planeta, disco voador, dado, moeda, estrela, coração, cubo, esfera, cilindro, cone, pirâmide, prisma, rosca, cápsula, chão… e **texto 3D** (`texto 'OLÁ'`)
- **Quantidades**: "4 cadeiras", "duas esferas", "cadeiras" (plural)
- **Parâmetros**: "6 pernas", "3 andares", "7 pontas", "10 degraus", "6 torres", "3 aletas"
- **Cores** (40+, hex `#FF8800`, "azul escuro") e **materiais** com texturas procedurais (madeira, metal, ouro, prata, cobre, vidro, pedra, mármore, tijolo, concreto, tecido, plástico, borracha, cerâmica, grama, xadrez)
- **Partes**: "casa azul com **telhado** vermelho", "pinte as **pernas** de preto"
- **Medidas**: "2 metros de altura", "50 cm de largura", "raio 30 cm"
- **Relações**: "em cima de", "ao lado", "ao redor", "dentro de", "mesa com 4 cadeiras" (já posiciona ao redor)
- **Estilo**: "low poly", "suave", "grande", "alto", "fino", "redondo"
- **Edição**: pintar, aumentar/diminuir ("2 vezes maior", "metade"), esticar, girar ("45 graus"), mover, duplicar, espelhar, suavizar, simplificar, remover, desfazer/refazer
- **Foto por comando**: "transforme a foto em vaso", "foto em relevo", "foto com mais volume"
- **Voz**: botão de microfone (reconhecimento de fala do Android)

Exemplos:
```
mesa redonda de madeira com 4 cadeiras pretas
casa azul de 2 andares com telhado vermelho
esfera dourada em cima de um cubo de mármore
foguete vermelho com 3 aletas
castelo com 6 torres
texto 'VORTICIS' dourado
pinte o telhado de verde
deixe 2 vezes maior
gire 45 graus
adicione uma árvore ao lado
```

### 3. Exportação (12 formatos)

| Formato | Uso |
|---|---|
| **GLB / glTF 2.0** | Web, AR, Android, Unity, Godot, Three.js (com texturas) |
| **OBJ + MTL** | Blender, Maya, 3ds Max, C4D (ZIP com texturas) |
| **FBX 7.4 binário** | Unity, Unreal, Maya, Blender (textura embutida) |
| **Collada (DAE)** | SketchUp, Blender, motores de jogo |
| **USDZ** | AR no iPhone/iPad (Quick Look), Apple Vision |
| **STL** | Impressão 3D (mm, Z para cima, 1 m da cena = 100 mm) |
| **3MF** | Impressão 3D com cores (Bambu, Prusa, Cura) |
| **PLY** | Cores por vértice (MeshLab, CloudCompare) |
| **OFF** | Geometria simples |
| **X3D** | Padrão web ISO |
| **VRML (WRL)** | Impressão 3D colorida |
| **ZIP com todos** | Um botão exporta tudo de uma vez |

Os arquivos vão para **Downloads/Vorticis3D** e podem ser compartilhados (WhatsApp, Drive, e-mail…).

## Validação

Os exportadores são testados automaticamente (`./gradlew :core:test`). Durante o desenvolvimento, os arquivos gerados passaram por:
- **Khronos glTF Validator**: 0 erros, 0 avisos (GLB e glTF)
- **Assimp**: GLB, glTF, OBJ, FBX (com textura embutida), DAE, STL, 3MF, PLY, OFF e X3D abrem corretamente
- **Pixar USD**: USDZ abre, com dados alinhados a 64 bytes, materiais ligados e texturas resolvidas
- **trimesh**: STL fechado (*watertight*) e com volume positivo

O VRML (WRL) não passou por validador automático, porque o Assimp não lê esse formato.

## Estrutura

```
core/   Kotlin puro (testável na JVM)
  mind/    Vorticis Mind: interpretador de linguagem natural, edição, texto 3D
  gen/     biblioteca de 44 objetos paramétricos, materiais e texturas procedurais
  geom/    malhas, primitivas, normais automáticas, suavização Taubin, low poly
  photo/   segmentação própria e Foto→3D (Poisson, revolução, extrusão, relevo)
  export/  GLB, glTF, OBJ, FBX, DAE, USDZ, STL, 3MF, PLY, OFF, X3D, WRL, ZIP, PNG
app/    Android (Kotlin + Jetpack Compose + OpenGL ES 3.0 + ML Kit)
```

## Compilar no computador (opcional)

```bash
./gradlew :core:test :app:assembleRelease
```
O APK sai em `app/build/outputs/apk/release/app-release.apk`. Requer JDK 17 e Android SDK 35.

## Limitações honestas e próximos passos

- Com uma única foto, a parte de trás do objeto é **estimada**: a frente recebe a foto e o verso é espelhado. Com várias fotos, o verso é real, mas os ângulos são presumidos (fotos igualmente espaçadas); fotos tortas ou a distâncias diferentes deformam o resultado. Para fidelidade de nível Meshy/Tripo seria preciso um modelo de difusão multivista ou de reconstrução 3D (TripoSR, SF3D, TRELLIS). Esses modelos pedem GPU com 6 GB de VRAM ou mais e ainda não rodam bem em celular.
- A Vorticis Mind entende frases com objetos e atributos conhecidos. Ela não é um LLM. Um próximo passo é integrar um LLM local (por exemplo, Gemma via MediaPipe LLM Inference) para ampliar o vocabulário, gerando o mesmo plano de cena que a Mind já executa.
- A chave de assinatura incluída (`app/vorticis-test.keystore`) serve só para testes. Para publicar na Play Store, gere uma chave privada.
