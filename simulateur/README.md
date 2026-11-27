# Simulateur d'animations

Une page web, sans rien à installer, qui joue les animations de Ruban LED sur trois rubans vus de
face : le tableau de bord en haut, les deux portières sur les côtés. Ce sont les mêmes calculs, les
mêmes délais entre images et les mêmes couleurs que dans l'app (`LedService.java`,
`HoldShow.java`), avec le câblage réel : un seul signal de 20 segments, le tableau de bord affiche
les segments 15 (à gauche) à 1 (à droite), chaque portière les segments 1 à 10 de l'arrière vers
l'avant. Les deux portières sont donc toujours identiques.

## L'ouvrir

**Le plus simple** : télécharger le dépôt (bouton vert « Code » → « Download ZIP » sur GitHub, ou
`git clone`), puis double-cliquer sur `simulateur/index.html`. Il s'ouvre dans le navigateur, avec
les réglages des animations intégrés à la page.

**Avec le fichier du dépôt** : pour qu'il lise `animations/animations.json` (celui que l'app
télécharge), le servir depuis la racine du dépôt :

```bash
python -m http.server 8000
```

puis ouvrir <http://localhost:8000/simulateur/>. En bas des réglages s'affiche « Chargé depuis le
dépôt : version … ». Sans serveur, « Ouvrir un fichier… » fait la même chose.

## L'utiliser

- Un bouton par animation de l'app : **Allumage**, **Extinction** (3 fois chacune, comme dans
  l'app), **Alerte porte** (clignotement rouge, 6 s), **Marche arrière** (clignotement jaune → ambre → orange → rouge, approche simulée),
  **Autosteer** (bleu 1 s), **Recharge** (jauge à 62 %), puis les animations à l'arrêt :
  **Respiration lente**, **Croisement**, **Du centre vers les bouts**, **Scintillement**, **Vague
  de couleur** (10 s chacune, avec leur fondu d'entrée et de sortie, comme le test de l'app).
- **Couleur permanente** : la pastille de couleur. Les animations l'utilisent comme dans l'app.
- La luminosité est montrée telle que l'œil la voit : une LED à 20 % paraît bien plus forte que
  20 %.

## Modifier les animations à l'arrêt, sans réinstaller l'app

Depuis la 0.28, l'app lit les réglages des animations à l'arrêt dans `animations/animations.json`,
que le bouton **« Mise à jour des animations »** (carte « Animations » de l'app) télécharge depuis
la branche `main` de ce dépôt.

1. **« Réglages des animations d'arrêt »** sous les boutons : cliquer une animation à l'arrêt
   affiche ses réglages (fond, traîne, durées, minimum de la respiration, chance du
   scintillement…). L'animation repart à chaque changement.
2. **Nouvelle animation enregistrée** (facultatif) : un nom, une durée de boucle et une formule
   qui donne le niveau (0 à 1) de chaque segment du tableau de bord selon `x` (0 au bout droit,
   1 au bout gauche), `t` (secondes) et `i` (0 à 14). « Enregistrer l'animation » calcule ses
   images (toutes les `image_ms`, 150 ms) et l'ajoute aux animations à l'arrêt : l'app la tirera au
   hasard avec les autres et la jouera en boucle jusqu'au départ, dans la couleur permanente.
   Une animation du même nom est remplacée.
3. **« Exporter animations.json »** : demande une phrase sur ce qui change (affichée dans l'app),
   augmente la version et télécharge le fichier.
4. Remplacer `animations/animations.json` du dépôt par ce fichier, commiter et pousser sur `main`.
5. Dans la voiture : **« Mise à jour des animations »**. L'app montre chaque étape (recherche sur
   GitHub, téléchargement avec sa barre, vérification du fichier, mise en place) ; la prochaine
   animation à l'arrêt utilise le nouveau fichier. Un fichier refusé (valeur hors limites, format
   inconnu) laisse les animations en place, avec la raison. **« Animations d'origine »** revient
   à celles intégrées à l'app.

## Le fichier `animations.json`

```json
{
  "format": 1,
  "version": 1,
  "date": "2026-11-25",
  "description": "ce qui change, affiché dans l'app",
  "image_ms": 150,
  "arret": { "une_fois_sur": 1 },
  "marche_arriere": { "rouge_a": 2, "jaune_a": 8 },
  "respiration": { "min": 5, "souffle_s": 5 },
  "croisement": { "fond": 0, "traine": 0.07, "trajet_s": 3.2, "pause_s": 0.6 },
  "centre": { "fond": 0, "traine": 0.07, "trajet_s": 1.8, "pause_s": 0.6 },
  "scintillement": { "fond": 0, "chance": 3, "descente": 0.7 },
  "vague": { "ecart": 50, "periode_s": 4 },
  "enregistrees": [
    { "nom": "Va-et-vient", "image_ms": 150, "images": [[0, 0, 0, 0, 0, 0, 24, 100, 24, 0, 0, 0, 0, 0, 0]] }
  ]
}
```

| Clé | Sens | Limites |
|---|---|---|
| `image_ms` | délai entre deux images des animations à l'arrêt | 40 à 1000 |
| `arret.une_fois_sur` | l'animation se joue à 1 arrêt sur N (1 = à chaque arrêt) — app 0.30 | 1 à 20 |
| `marche_arriere.rouge_a`, `jaune_a` | niveau du radar arrière le plus proche (1 = tout près … 10) : rouge à `rouge_a` et en dessous, jaune à `jaune_a` et au-dessus, ambre puis orange entre les deux ; sans obstacle, jaune ; toujours en clignotement, plus rapide à l'approche — app 0.31 | 1 à 10, `jaune_a` > `rouge_a` |
| `respiration.min` | luminosité au creux du souffle, en % de la luminosité du moment | 0 à 100 |
| `respiration.souffle_s` | durée d'un souffle | 0,5 à 60 s |
| `croisement` / `centre` : `fond` | luminosité du ruban hors des lumières qui bougent (0 = éteint) | 0 à 100 % |
| `croisement` / `centre` : `traine` | longueur de la traîne (en fraction du tableau de bord) | 0,01 à 1 |
| `croisement` / `centre` : `trajet_s`, `pause_s` | durée d'un passage, pause avant le suivant | 0,3 à 30 s, 0 à 30 s |
| `scintillement.fond` | luminosité entre les étoiles | 0 à 100 % |
| `scintillement.chance` | chance qu'un segment éteint s'allume, à chaque image | 0 à 100 % |
| `scintillement.descente` | ce qui reste d'une étoile d'une image à l'autre | 0,1 à 0,98 |
| `vague.ecart`, `vague.periode_s` | écart de teinte autour de la couleur permanente, période | 0 à 180°, 0,5 à 60 s |
| `enregistrees` | au plus 20, chacune 1 à 2000 images de 15 niveaux (0 à 100, segment 1 = bout droit) | |

L'allumage, l'extinction, l'alerte, l'Autosteer, la jauge et les couleurs de la marche arrière ne sont pas dans le
fichier : les changer demande encore une nouvelle version de l'app.
