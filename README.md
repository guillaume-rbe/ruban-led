# Ruban LED

Petite app Android pour l'écran ZIQI A007 (Tesla Model 3/Y) qui pilote en Bluetooth LE un ruban LED
Zengge (app « ZENGGE », `com.zengge.blev2`) selon l'état des portes de la voiture.

Séparée de Binnacle : elle ne lit que les portes, **en lecture seule**, auprès de l'app CAN d'origine
(`com.xbs.canapp`, code 15 `doorval`, seules transactions `regPropIdList`/`unregPropIdList`, comme le
`CanFeed` de Binnacle). Rien n'est envoyé à la voiture ni au MCU.

## Ce qu'elle fait

- **Rétroviseurs = présence** (si configurés, voir plus bas) : c'est le signe le plus fiable que
  le propriétaire est là. Quand ils se déplient (il arrive), animation d'allumage jouée **3 fois**,
  puis couleur permanente ; quand ils se replient (il part), animation d'extinction **3 fois**, puis
  ruban éteint. Rétros repliés, rien ne s'allume (ni portes, ni warnings, ni jauge de recharge),
  pour ne pas gaspiller d'énergie. Rétros dépliés, une porte qui s'ouvre fait clignoter le ruban.
- **Animation d'entrée** (sans rétroviseurs configurés) : à la première porte ouverte après le réveil de la voiture (écran rallumé,
  ruban rebranché après plus d'une minute, démarrage de l'app), le ruban se remplit progressivement
  (~5 s, **3 fois**) dans la couleur permanente (ambiante), puis reste dessus. Les portes se remplissent de l'arrière vers l'avant ; le tableau de
  bord part des deux bouts.
- **Clignotement** : ensuite, chaque porte, coffre ou frunk qui s'ouvre (même si une autre est déjà
  ouverte) fait clignoter le ruban (couleur d'alerte, rouge par défaut, 100 % ↔ 1 %, 400 ms, 2 min max).
- **Après chaque animation** : toujours la couleur permanente (la jauge pendant la recharge), ou
  ruban éteint si les rétros sont repliés.
- **Warnings** : tant que les feux de détresse sont allumés (les deux clignotants allumés en même
  temps dans le flux CAN, code 9), le ruban clignote dans la couleur d'alerte.
- **Recharge** : pendant la charge (code 9 `chargelight`), et seulement rétros dépliés, le ruban
  devient une jauge verte du pourcentage de batterie (code 11, décodé comme Binnacle), remplie
  depuis la droite du tableau de bord, son extrémité pulsant.
- **Veille** : quand l'écran s'éteint (voiture endormie), la lumière se retire progressivement
  (ordre inverse de l'entrée, ~3 s, 3 fois), puis le ruban s'éteint. Utile car il est alimenté par
  l'USB de la boîte à gants, qui reste alimenté tant que la voiture est éveillée (Sentinelle
  comprise).
- **Mode nuit**, activé par défaut : de 19 h (heure d'hiver) ou 21 h (heure d'été) à 7 h, tout le
  ruban (couleur permanente, animations, jauge) à 20 %. Heures et luminosité réglables.
- **Deux couleurs** réglables dans l'app, chacune avec sa roue et sa jauge de luminosité :
  couleur permanente, et couleur d'alerte (rouge 100 % par défaut, réservée aux portes, coffre, frunk et warnings).
- Écran sombre en cartes : état, rétroviseurs, couleurs, derniers événements (heure de chaque porte,
  connexion du ruban… pour voir d'où vient un retard), actions, règles, ruban et mise à jour.

## Rétroviseurs

L'état des rétroviseurs n'est pas dans la carte des champs de Binnacle : l'app le trouve elle-même.
Carte « Rétroviseurs » : rétros dépliés, toucher **1** (l'app lit toutes les données de l'app CAN,
codes 0–127, en lecture seule comme le logger de Binnacle) ; replier les rétros depuis l'écran de la
Tesla, toucher **2** ; choisir la donnée qui a changé (la première en général). Elle est enregistrée
et l'app ne lit ensuite que ce code en plus des portes. Si rien ne change, l'app CAN ne publie pas
cet état : le ruban garde les règles écran / portes. « Oublier » efface le réglage.

## Priorité et réactivité

- Les règles tournent sur leur propre fil de priorité haute (plus sur le fil de l'écran), le fil
  Bluetooth aussi ; la connexion BLE est en intervalle court et se refait toutes les 2,5 s.
- Service au premier plan (notification « Ruban LED actif »), relancé au démarrage, après une mise à
  jour et toutes les 15 min s'il a été arrêté ; le processeur reste éveillé pendant les animations.
- Au premier lancement, Android demande de laisser l'app hors de l'optimisation de la batterie :
  accepter. Sur l'A007, ajouter aussi `app.rubanled` dans `/data/system/appPowerSaveConfig.xml`
  (voir plus bas).

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

Si Android refuse la connexion de l'app (« socket failed: EPERM », vu sur l'A007 quand la
permission Internet est arrivée par une mise à jour : Android la donne mais le processus ne l'a
pas), l'app passe par le gestionnaire de téléchargements d'Android, qui télécharge à sa place.
Si cela échoue aussi : `adb uninstall app.rubanled` puis `adb install ruban-led.apk` (les réglages
sont à refaire).

Publier une version : augmenter `android:versionCode` dans `AndroidManifest.xml`, pousser la
branche `ruban-led`, puis `bash release.sh` (crée la release avec `gh`, sans la marquer « latest »).
