from glob import iglob
import re
from itertools import chain
from collections import Counter
import json

RULE = re.compile(r'\(standard:([\w-]+)\)')

i1 = iglob(r'lib-multisrc\*\build\reports\ktlint\ktlintMainSourceSetCheck\ktlintMainSourceSetCheck.txt')
i2 = iglob(r'src\*\*\build\reports\ktlint\ktlintMainSourceSetCheck\ktlintMainSourceSetCheck.txt')

result = Counter()

for file in chain(i1, i2):
    with open(file) as f:
        result.update(RULE.findall(f.read()))

print(json.dumps(dict(result.most_common()), indent=2))
