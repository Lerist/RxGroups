/*
 * Copyright (C) 2016 Airbnb, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.airbnb.rxgroups;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.observers.TestSubscriber;
import rx.subjects.PublishSubject;
import rx.subjects.ReplaySubject;
import rx.subscriptions.Subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

public class SubscriptionProxyTest {
  @Test public void testInitialState() {
    Observable<Integer> observable = Observable.create(new Observable.OnSubscribe<Integer>() {
      @Override
      public void call(Subscriber<? super Integer> subscriber) {
        subscriber.onNext(1234);
        subscriber.onCompleted();
      }
    });
    SubscriptionProxy<Integer> proxy = SubscriptionProxy.create(observable);
    assertThat(proxy.isUnsubscribed()).isEqualTo(false);
    assertThat(proxy.isCancelled()).isEqualTo(false);
  }

  @Test public void testSubscriptionState() {
    TestSubscriber<String> subscriber = new TestSubscriber<>();
    PublishSubject<String> subject = PublishSubject.create();
    SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

    proxy.subscribe(subscriber);
    assertThat(proxy.isUnsubscribed()).isEqualTo(false);
    assertThat(proxy.isCancelled()).isEqualTo(false);

    proxy.unsubscribe();

    assertThat(proxy.isUnsubscribed()).isEqualTo(true);
    assertThat(proxy.isCancelled()).isEqualTo(false);

    proxy.cancel();

    assertThat(proxy.isUnsubscribed()).isEqualTo(true);
    assertThat(proxy.isCancelled()).isEqualTo(true);
  }

  @Test public void testSubscribe() {
    TestSubscriber<Integer> subscriber = new TestSubscriber<>();
    Observable<Integer> observable = Observable.create(new Observable.OnSubscribe<Integer>() {
      @Override
      public void call(Subscriber<? super Integer> subscriber) {
        subscriber.onNext(1234);
        subscriber.onCompleted();
      }
    });
    SubscriptionProxy<Integer> proxy = SubscriptionProxy.create(observable);

    proxy.subscribe(subscriber);

    subscriber.assertCompleted();
    subscriber.assertValue(1234);
  }

  @Test public void testUnsubscribe() {
    TestSubscriber<String> subscriber = new TestSubscriber<>();
    PublishSubject<String> subject = PublishSubject.create();
    SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

    proxy.subscribe(subscriber);
    proxy.unsubscribe();

    subject.onNext("Avanti!");
    subject.onCompleted();

    assertThat(proxy.isUnsubscribed()).isEqualTo(true);
    subscriber.awaitTerminalEvent(10, TimeUnit.MILLISECONDS);
    subscriber.assertNotCompleted();
    subscriber.assertNoValues();
  }

  static class TestOnUnsubscribe implements Action0 {
    boolean called = false;

    @Override public void call() {
      called = true;
    }
  }

  @Test public void testCancelShouldUnsubscribeFromSourceObservable() {
    TestSubscriber<String> subscriber = new TestSubscriber<>();
    final TestOnUnsubscribe onUnsubscribe = new TestOnUnsubscribe();
    Observable<String> observable = Observable.create(new Observable.OnSubscribe<String>() {
      @Override public void call(final Subscriber<? super String> subscriber) {
        subscriber.add(Subscriptions.create(onUnsubscribe));
      }
    });
    SubscriptionProxy<String> proxy = SubscriptionProxy.create(observable);

    proxy.subscribe(subscriber);
    proxy.cancel();

    assertThat(proxy.isUnsubscribed()).isEqualTo(true);
    assertThat(proxy.isCancelled()).isEqualTo(true);
    assertThat(onUnsubscribe.called).isEqualTo(true);
  }

  @Test public void testUnsubscribeShouldNotUnsubscribeFromSourceObservable() {
    TestSubscriber<String> subscriber = new TestSubscriber<>();
    final TestOnUnsubscribe onUnsubscribe = new TestOnUnsubscribe();
    Observable<String> observable = Observable.create(new Observable.OnSubscribe<String>() {
      @Override public void call(final Subscriber<? super String> subscriber) {
        subscriber.add(Subscriptions.create(onUnsubscribe));
      }
    }).share();
    SubscriptionProxy<String> proxy = SubscriptionProxy.create(observable);

    proxy.subscribe(subscriber);
    proxy.unsubscribe();

    assertThat(proxy.isUnsubscribed()).isEqualTo(true);
    assertThat(proxy.isCancelled()).isEqualTo(false);
    assertThat(onUnsubscribe.called).isEqualTo(false);
  }

  @Test public void testUnsubscribeBeforeEmit() {
    TestSubscriber<String> subscriber = new TestSubscriber<>();
    ReplaySubject<String> subject = ReplaySubject.create();
    SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

    proxy.subscribe(subscriber);
    proxy.unsubscribe();

    subscriber.assertNotCompleted();
    subscriber.assertNoValues();

    subject.onNext("Avanti!");
    subject.onCompleted();

    proxy.subscribe(subscriber);
    subscriber.assertCompleted();
    subscriber.assertValue("Avanti!");
  }

  @Test public void shouldCacheResultsWhileUnsubscribedAndDeliverAfterResubscription() {
    TestSubscriber<String> subscriber = new TestSubscriber<>();
    ReplaySubject<String> subject = ReplaySubject.create();
    SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

    proxy.subscribe(subscriber);
    proxy.unsubscribe();

    subscriber.assertNoValues();

    subject.onNext("Avanti!");
    subject.onCompleted();

    proxy.subscribe(subscriber);

    subscriber.awaitTerminalEvent(3, TimeUnit.SECONDS);
    subscriber.assertValue("Avanti!");
  }

  @Test public void shouldRedeliverSameResultsToDifferentSubscriber() {
    // Use case: When rotating an activity, ObservableManager will re-subscribe original request's
    // Observable to a new Observer, which is a member of the new activity instance. In this
    // case, we may want to redeliver any previous results (if the request is still being
    // managed by ObservableManager).
    TestSubscriber<String> subscriber = new TestSubscriber<>();
    ReplaySubject<String> subject = ReplaySubject.create();
    SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

    proxy.subscribe(subscriber);

    subject.onNext("Avanti!");
    subject.onCompleted();

    proxy.unsubscribe();

    TestSubscriber<String> newSubscriber = new TestSubscriber<>();
    proxy.subscribe(newSubscriber);

    newSubscriber.awaitTerminalEvent(3, TimeUnit.SECONDS);
    newSubscriber.assertCompleted();
    newSubscriber.assertValue("Avanti!");

    subscriber.assertCompleted();
    subscriber.assertValue("Avanti!");
  }

  @Test public void multipleSubscribesForSameObserverShouldBeIgnored() {
    TestSubscriber<String> subscriber = new TestSubscriber<>();
    PublishSubject<String> subject = PublishSubject.create();
    SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

    proxy.subscribe(subscriber);
    proxy.subscribe(subscriber);
    proxy.unsubscribe();

    subject.onNext("Avanti!");
    subject.onCompleted();

    assertThat(proxy.isUnsubscribed()).isEqualTo(true);
    subscriber.awaitTerminalEvent(10, TimeUnit.MILLISECONDS);
    subscriber.assertNotCompleted();
    subscriber.assertNoValues();
  }

  @Test public void shouldKeepDeliveringEventsAfterResubscribed() {
    TestSubscriber<String> subscriber = new TestSubscriber<>();
    ReplaySubject<String> subject = ReplaySubject.create();
    SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

    proxy.subscribe(subscriber);
    subject.onNext("Avanti 1");
    proxy.unsubscribe();
    subscriber = new TestSubscriber<>();
    proxy.subscribe(subscriber);

    subject.onNext("Avanti!");

    subscriber.assertValues("Avanti 1", "Avanti!");
  }
}
