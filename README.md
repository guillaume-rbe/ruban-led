# Ruban LED

Petite app Android pour l'écran ZIQI A007 (Tesla Model 3/Y) qui pilote en Bluetooth LE un ruban LED
Zengge (app « ZENGGE », `com.zengge.blev2`) selon l'état des portes de la voiture.

Séparée de Binnacle : elle ne lit que les portes, **en lecture seule**, auprès de l'app CAN d'origine
(`com.xbs.canapp`, code 15 `doorval`, seules transactions `regPropIdList`/`unregPropIdList`, comme le
`CanFeed` de Binnacle). Rien n'est envoyé à la voiture ni au MCU.

## Ce qu'elle fait

- **Animation d'entrée** : à la première porte ouverte après le réveil de la voiture (écran rallumé,
  ruban rebranché après plus d'une minute, démarrage de l'app), le ruban se remplit progressivement
  (~5 s) dans la couleur des animations, puis passe à la couleur permanente.
  Les portes se remplissent de l'arrière vers l'avant ; le tableau de bord part des deux bouts.
- **Clignotement** : ensuite, chaque ouverture de porte, coffre ou frunk fait clignoter le ruban
  (couleur des animations, 100 % ↔ 1 %, 400 ms, 2 min max), puis le remet comme avant.
- **Warnings** : tant que les feux de détresse sont allumés (les deux clignotants allumés en même
  temps dans le flux CAN, code 9), le ruban clignote dans la couleur des animations.
- **Veille** : quand l'écran s'éteint (voiture endormie), le ruban s'éteint. Utile car il est
  alimenté par l'USB de la boîte à gants, qui reste alimenté tant que la voiture est éveillée
  (Sentinelle comprise).
- **Deux couleurs** réglables dans l'app, chacune avec sa roue et sa jauge de luminosité :
  couleur permanente, et couleur des animations (rouge 100 % par défaut).
- Option : baisser la luminosité la nuit (heures réglables), désactivée par défaut.

## Câblage constaté

Le contrôleur a trois sorties qui reçoivent **le même signal** (20 segments d'environ 7,5 cm) :
les deux portes (75 cm) affichent les segments 1 à 10 depuis l'arrière, le tableau de bord (110 cm)
les segments 1 à 15 depuis la droite. Les deux portes ne peuvent donc pas être pilotées séparément.

## Protocole

Voir [PROTOCOLE.md](PROTOCOLE.md) (capturé avec le journal HCI Android pendant l'utilisation de
l'app ZENGGE). Points à retenir :

- service `FFFF`, écriture `FF01` **avec réponse** (propriétés 0x0C) : en écriture sans réponse, le
  ruban perd des commandes quand elles s'enchaînent ;
- les notifications d'état (`FF02`) décrivent la commande **précédente** : l'app retient ce qu'elle
  envoie et ignore les réponses qui arrivent moins de 1,5 s après un envoi ;
- « allumer » envoyé juste après une couleur remet du blanc : allumer d'abord, couleur ensuite.

## Compiler et installer

Outils du SDK Android seulement (pas de Gradle) : build-tools 36, plateforme android-35, le JDK
d'Android Studio, Python.

```bash
bash build.sh
adb install -r build/ruban-led.apk
```

Au premier lancement, saisir l'adresse Bluetooth du ruban dans l'app (elle n'est pas dans le code)
puis « Enregistrer ». Sur l'A007, ajouter `app.rubanled` dans `/data/system/appPowerSaveConfig.xml`
(même ligne que `com.shilapi.xcertplay`, `autolaunch="2" secondarylaunch="2"`) pour qu'elle démarre
avec l'écran.

Signée avec la clé de debug du PC (`~/.android/debug.keystore`). Garder toujours la même clé :
sinon Android refuse la mise à jour.

## Mises à jour depuis l'app

Le bouton « Mise à jour » cherche dans les releases de `guillaume-rbe/binnacle` la plus récente
taguée `ruban-led-v<versionCode>`, plus récente que la version installée. Il télécharge
`ruban-led.apk`, vérifie son SHA-256 (`ruban-led.apk.sha256`), puis l'installe avec
l'installateur d'Android, verrouillé sur ce paquet : Android affiche sa propre confirmation et
n'accepte qu'une APK signée avec la même clé. La première fois, autoriser « Installer des
applis inconnues » pour Ruban LED (l'app ouvre l'écran d'Android).

Publier une version : augmenter `android:versionCode` dans `AndroidManifest.xml`, pousser la
branche `ruban-led`, puis `bash release.sh` (crée la release avec `gh`, sans la marquer « latest »).
