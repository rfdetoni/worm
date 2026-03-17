package br.com.liviacare.worm;

import br.com.liviacare.worm.api.Deletable;
import br.com.liviacare.worm.api.Persistable;

public abstract class ActiveRecord<T extends ActiveRecord<T, ID>, ID> implements Persistable<T>, Deletable<T, ID> {

}
