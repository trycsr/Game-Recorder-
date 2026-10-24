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
| Detecção do jogo aberto usava uma função interna só de Samsung antigo (fazia o app fechar) | Usa o "Acesso aos dados de uso" do Android |
| Sem pedido de permissão de sobreposição | O app pede "Sobrepor a outros apps" e "Acesso aos dados de uso" |
| Função `setProcessForeground`, removida no Android 8, fazia o app fechar ao entrar na tela principal | Desativada |
| Permissão de gravar a tela era obtida por um atalho exclusivo de sistema | Usa a janela normal do Android ("Iniciar gravação?") |
| Erros fechavam o app sem explicação | O erro é salvo e mostrado na próxima abertura, com botão para copiar |

A gravação em si usa a API do próprio Android (MediaProjection no Android 5+), então não dependia do servidor.

## Como instalar

1. Desinstale o Game Recorder+ original, se estiver instalado (a assinatura é diferente).
2. Instale o APK acima (permita "fontes desconhecidas").
3. Abra o app e aceite as duas permissões que ele pedir (sobreposição e acesso aos dados de uso).
4. Se um jogo não aparecer na lista, use a opção de adicionar jogos/apps instalados.
5. Abra o jogo: o botão de gravar aparece por cima dele.
6. Na primeira vez que tocar em gravar, o Android pergunta se pode gravar a tela. Aceite (se aparecer "Não mostrar novamente", marque) e toque no botão de gravar de novo.

## Limitações

- O app contém bibliotecas nativas só para ARM 32-bit. Aparelhos que rodam apenas 64-bit podem não abrir ou não gravar.
- Upload para o YouTube e anúncios/estatísticas (Tapjoy, Google Analytics) não foram mexidos e provavelmente não funcionam mais.
- O app é antigo (feito para Android 6). Em versões muito novas do Android pode haver falhas que não têm relação com o servidor.

## Como gerar o APK de novo

```sh
patch/build.sh caminho/do/apk-original.apk /tmp/build
```

- `patch/java/com/offline/OfflineHelper.java`: lista de jogos local, no lugar do servidor.
- `patch/java/com/sec/game/gamecast/floatingui/OfflineRuntime.java`: detecção do jogo aberto, pedido de permissões e registro de erros.
- `patch/apply_patch.py`: altera o código smali do app decompilado.
