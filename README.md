# Mouse Without Borders for Android by Brendler©

App Android que entra como "mais uma máquina" no **Mouse sem Bordas (Mouse Without Borders)** do **Microsoft PowerToys**.
O mouse e o teclado do PC passam a controlar o tablet ou celular, como se fosse outro computador do layout.

> Projeto independente, sem ligação com a Microsoft. "Mouse Without Borders" e "PowerToys" são nomes da Microsoft.
> O protocolo foi implementado a partir do código aberto do PowerToys (licença MIT).

## O que funciona
- Conexão pelo **nome do PC** e pela **Chave de Segurança**, como no PowerToys (o IP também é aceito).
- Mouse entra pela borda e volta ao Windows; clique, arrastar, clique longo (botão direito) e rolagem.
- Teclado ABNT2 com acentos (~ ^ ´ `) e Ç.
- Copiar, colar e recortar texto entre PC e Android.
- Arquivos e imagens: do PC para o Android (Ctrl+C no PC e levar o mouse ao tablet) e do Android para o PC (botão "Escolher" ou menu Compartilhar).
- Layout das máquinas mostrado no app (o último recebido do PC fica guardado); visual no estilo do PowerToys (tema claro e escuro).
- Troca por atalho (Ctrl+Alt+F1…F4 no PC): o mouse chega no meio da tela e volta ao PC pela borda do lado em que o PC fica no layout. Sem o layout, a primeira borda usada é aprendida.
- Conexão automática ao ligar o tablet e quando a rede volta.

## Requisitos
- PC com Windows e PowerToys (Mouse sem Bordas ligado). Testado com PowerToys 0.102.
- Android 8.0 ou mais novo.
- PC e Android na mesma rede.

## Como usar
1. No PC: PowerToys › Mouse sem Bordas › copie a **Chave de Segurança** e veja o **Nome do host deste dispositivo**.
2. No Android: abra o app e toque em **Ativar** (serviço de acessibilidade, necessário para mover o cursor, tocar e digitar).
3. Digite a chave e o nome do PC e toque em **Conectar-se**.
4. No PowerToys, coloque o nome do Android (mostrado no app em "Nome do host deste dispositivo") no layout das máquinas.
5. Leve o mouse até a borda da tela do PC.

Em aparelhos Xiaomi/POCO, para conectar sozinho ao ligar: ajustes do app › **Início automático** ligado e bateria **Sem restrições**.

O PowerToys só envia o layout ao Android quando ele muda. Se o app não mostrar todas as máquinas, mude o layout no PowerToys com o Android conectado (arraste um quadro para outra posição).

## Como compilar
Abra a pasta no **Android Studio** e clique em **Run ▶**.

Release assinado: defina em `~/.gradle/gradle.properties` (fora do projeto) `MWB_RELEASE_STORE_FILE`, `MWB_RELEASE_STORE_PASSWORD`, `MWB_RELEASE_KEY_ALIAS` e `MWB_RELEASE_KEY_PASSWORD` e rode `gradlew assembleRelease`. Sem essas propriedades o release sai sem assinatura.

## Estrutura
- `protocol/` – protocolo do Mouse sem Bordas (pacotes, criptografia AES-256, conexão, área de transferência, transferência de arquivos, descoberta do PC pelo nome). Código Kotlin puro, testável fora do Android.
- `RemoteInput.kt` – mouse e teclado do Windows → ações no Android.
- `KvmAccessibilityService.kt` – cursor, toques, rolagem e digitação.
- `MwbController.kt`, `PcFinder.kt`, `FileStore.kt`, `ShareActivity.kt`, `MainActivity.kt` – conexão, busca do PC, arquivos, compartilhar e tela.
- `tools/` – PCs simulados em Python e testes em Kotlin (JVM) do protocolo.

## Contato
- Problemas e sugestões: [Issues do projeto](https://github.com/fbrendler/mwb-android/issues) (no app: Sobre › Relatar, que já copia o log).

## Licença
MIT © 2026 Brendler. Veja o arquivo [LICENSE](LICENSE).
