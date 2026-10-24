# Game Recorder+ (offline)

Versão modificada do **Game Recorder+ 0.0.41** da Samsung (`com.sec.game.gamecast.global`)
que funciona **sem os servidores da Samsung**, desligados em 2018.

**APK pronto:** [`release/GameRecorderPlus-offline-0.0.41.apk`](release/GameRecorderPlus-offline-0.0.41.apk)

## O que foi mudado

| Antes (precisava do servidor) | Agora (offline) |
|---|---|
| Aviso de "fim do serviço" ao abrir | Removido, o app abre direto |
| Exigia internet no primeiro uso | Verificação removida |
| Checagem de atualização obrigatória (Amazon DynamoDB) | Sempre "sem atualização" |
| Lista de jogos permitidos vinha do servidor | Jogos instalados são detectados no próprio celular (apps marcados como "jogo" pelo Android) |
| Cadastrar um jogo manualmente enviava para o servidor | Cadastro salvo só no banco de dados local; qualquer app pode ser adicionado |
| Lista de jogos proibidos vinha do servidor | Desativada |
| Bloqueio para aparelhos não-Samsung | Removido |

A gravação em si usa a API do próprio Android (MediaProjection no Android 5+), então não dependia do servidor.

## Como instalar

1. Desinstale o Game Recorder+ original, se estiver instalado (a assinatura é diferente).
2. Instale o APK acima (permita "fontes desconhecidas").
3. Abra o app. Se um jogo não aparecer na lista, use a opção de adicionar jogos/apps instalados.

## Limitações

- O app contém bibliotecas nativas só para ARM 32-bit. Aparelhos que rodam apenas 64-bit podem não abrir ou não gravar.
- Upload para o YouTube e anúncios/estatísticas (Tapjoy, Google Analytics) não foram mexidos e provavelmente não funcionam mais.
- O app é antigo (feito para Android 6). Em versões muito novas do Android pode haver falhas que não têm relação com o servidor.

## Como gerar o APK de novo

```sh
patch/build.sh caminho/do/apk-original.apk /tmp/build
```

- `patch/java/com/offline/OfflineHelper.java`: lógica local que substitui o servidor.
- `patch/apply_patch.py`: altera o código smali do app decompilado.
