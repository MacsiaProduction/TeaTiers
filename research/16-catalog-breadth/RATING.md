# Rating — 16-catalog-breadth

Prompt: ./prompt.md   ·   Date judged: <YYYY-MM-DD>

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model  | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|--------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus   |    -     |   -   |       -       |    -     |    -    |   -   |  -   |
| gpt    |    -     |   -   |       -       |    -     |    -    |   -   |  -   |
| gemini |    -     |   -   |       -       |    -     |    -    |   -   |  -   |
| kimi   |    -     |   -   |       -       |    -     |    -    |   -   |  -   |

<!-- Optional Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. -->

**Winner:** <model> — <one line: why it won>
**Reuse:** <what to lift into the code/docs, and where>
**Discard:** <claims to ignore — unverified dataset licenses, hallucinated datasets, fabricated row counts>

> Then update ../LEADERBOARD.md: **+1 Wins** for the winner, **+1 Runs judged** for
> each model scored.
