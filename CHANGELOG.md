# Nouveautés

Chaque version publiée a ici sa section `## <versionName>` : `release.sh` la reprend comme texte de la release GitHub (et refuse de publier sans elle).

## 0.16

- **Mise à jour sans Internet** : le bouton « Mise à jour » ne reste plus bloqué sur « Téléchargement… ». Sans connexion, il abandonne en quelques secondes (30 s au plus) et affiche « Mise à jour impossible : pas de connexion Internet ».

## 0.15

- **Nouvelle jauge de recharge** : une barre verte fixe au milieu du tableau de bord, aussi large que le pourcentage de batterie (50 % = la moitié du tableau de bord). De chaque bout, un remplissage vert court vers elle, en boucle (~5 s).
- Sur les portes, c'est un remplissage vert de l'arrière vers l'avant qui finit dans la barre (à l'avant de la porte). À 100 %, tout est vert fixe.
- Toujours seulement voiture déverrouillée.

## 0.14

- **Couleurs verrouillées** : les roues de couleur et les jauges de luminosité sont verrouillées (et grisées) par défaut, pour qu'un doigt qui fait défiler la page ne change plus une couleur par erreur (la couleur d'alerte était passée au bleu le 17 nov.).
- Un bouton dans la carte « Couleurs » les déverrouille ; elles se reverrouillent en quittant l'app ou 2 min après le dernier réglage.

## 0.13

- **Musique à la connexion CarPlay** (option, activée par défaut) : quand l'iPhone se connecte et que rien ne joue 3 s plus tard, l'app envoie « lecture » à CarPlay, et l'iPhone relance sa dernière appli audio (YouTube Music…).
- Demande une fois l'accès aux notifications d'Android (bouton dans la carte « Règles »), pris en compte dès qu'il est donné, sans redémarrer l'app.

## 0.12

- **Verrouillage mieux reconnu** : un déverrouillage à moitié vu (un seul des deux flashs) n'est plus pris pour un verrouillage qui éteignait le ruban à l'arrivée.
- Une porte ouverte voiture « verrouillée » compte comme une arrivée, même pendant une animation.

## 0.11

- **Dépôt à part** : l'app a son propre dépôt, `guillaume-rbe/ruban-led`, et y cherche désormais ses mises à jour.
- README complet (captures, explications), documentation des données CAN utilisées, licence GPL-3.0 comme Binnacle.
