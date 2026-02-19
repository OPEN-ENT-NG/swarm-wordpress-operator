package fr.cgi.learninghub.swarm.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import fr.cgi.learninghub.swarm.resource.Wordpress;
import fr.cgi.learninghub.swarm.resource.WordpressInstallerSpec;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import org.apache.commons.io.IOUtils;

@ControllerConfiguration(namespaces = Constants.WATCH_CURRENT_NAMESPACE)
public class WordpressInstallerReconciler implements Reconciler<Wordpress> {

    // K8S API utility
    private final KubernetesClient k8sClient;

    public WordpressInstallerReconciler(KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    @Override
    public UpdateControl<Wordpress> reconcile(Wordpress resource, Context<Wordpress> context) {
        System.out.println("🛠️  Create / update Wordpress resource operator ! 🛠️");

        String namespace = resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();

        WordpressInstallerSpec.SiteSpec siteSpec = resource.getSpec().site();
        WordpressInstallerSpec.DatabaseSpec dbSpec = resource.getSpec().database();
        WordpressInstallerSpec.StorageSpec storageSpec = resource.getSpec().storage();

        // Create the Apache ConfigMap if it doesn't exist
        ConfigMap apacheConfigMap = k8sClient.configMaps().inNamespace(namespace).withName(name).get();
        if (apacheConfigMap == null) {
            apacheConfigMap = loadYaml(ConfigMap.class, "/apache.configmap.yml");
            apacheConfigMap.getMetadata().setNamespace(namespace);
            apacheConfigMap.getMetadata().setName(name + "-apache-configmap");
            apacheConfigMap.getMetadata().setOwnerReferences(Collections.singletonList(new OwnerReferenceBuilder()
                    .withUid(resource.getMetadata().getUid())
                    .withApiVersion(resource.getApiVersion())
                    .withName(name)
                    .withKind(resource.getKind())
                    .build()
            ));

            // Customize the ConfigMap
            apacheConfigMap.getData().computeIfPresent("httpd.conf", (key, conf) -> conf.replace("<<SITE_PATH>>", siteSpec.path()));

            k8sClient.configMaps().inNamespace(namespace).resource(apacheConfigMap).createOr(NonDeletingOperation::update);
        }

        // Create the Wordpress ConfigMap if it doesn't exist
        ConfigMap wordpressConfigMap = k8sClient.configMaps().inNamespace(namespace).withName(name).get();
        if (wordpressConfigMap == null) {
            wordpressConfigMap = loadYaml(ConfigMap.class, "/wordpress.configmap.yml");
            wordpressConfigMap.getMetadata().setNamespace(namespace);
            wordpressConfigMap.getMetadata().setName(name + "-wordpress-configmap");
            wordpressConfigMap.getMetadata().setOwnerReferences(Collections.singletonList(new OwnerReferenceBuilder()
                    .withUid(resource.getMetadata().getUid())
                    .withApiVersion(resource.getApiVersion())
                    .withName(name)
                    .withKind(resource.getKind())
                    .build()
            ));

            // Customize the ConfigMap
            wordpressConfigMap.getData().computeIfPresent("wp-config.php", (key, conf) -> {
                // Get keys from Wordpress API
                try (InputStream in = new URI("https://api.wordpress.org/secret-key/1.1/salt/").toURL().openStream()) {
                    return conf.replace("<<WP_KEYS>>", IOUtils.toString(in, StandardCharsets.UTF_8));
                } catch (URISyntaxException | IOException e) {
                    throw new RuntimeException(e);
                }
            });

            k8sClient.configMaps().inNamespace(namespace).resource(wordpressConfigMap).createOr(NonDeletingOperation::update);
        }

        // Create the Wordpress Service if it doesn't exist
        Service wpService = k8sClient.services().inNamespace(namespace).withName(name).get();
        if (wpService == null) {
            wpService = loadYaml(Service.class, "/wordpress.service.yml");
            wpService.getMetadata().setNamespace(namespace);
            wpService.getMetadata().setName(name);
            wpService.getMetadata().setOwnerReferences(Collections.singletonList(new OwnerReferenceBuilder()
                    .withUid(resource.getMetadata().getUid())
                    .withApiVersion(resource.getApiVersion())
                    .withName(name)
                    .withKind(resource.getKind())
                    .build()
            ));
            wpService.getSpec().getSelector().put("app", name);

            k8sClient.services().inNamespace(namespace).resource(wpService).createOr(NonDeletingOperation::update);
        }

            // Create the Wordpress PVC if it doesn't exist
            String pvcName = name + "-wordpress-data";
            PersistentVolumeClaim wpPvc = k8sClient.persistentVolumeClaims().inNamespace(namespace).withName(pvcName).get();
            if (wpPvc == null) {
                String size = storageSpec != null && storageSpec.size() != null ? storageSpec.size() : "1Gi";
                List<String> accessModes = storageSpec != null && storageSpec.accessModes() != null && !storageSpec.accessModes().isEmpty()
                    ? storageSpec.accessModes()
                    : Collections.singletonList("ReadWriteOnce");

                PersistentVolumeClaimBuilder pvcBuilder = new PersistentVolumeClaimBuilder()
                    .withNewMetadata()
                    .withName(pvcName)
                    .withNamespace(namespace)
                    .withOwnerReferences(Collections.singletonList(new OwnerReferenceBuilder()
                        .withUid(resource.getMetadata().getUid())
                        .withApiVersion(resource.getApiVersion())
                        .withName(name)
                        .withKind(resource.getKind())
                        .build()
                    ))
                    .endMetadata()
                    .withNewSpec()
                    .withAccessModes(accessModes)
                    .withNewResources()
                    .addToRequests("storage", new Quantity(size))
                    .endResources()
                    .endSpec();

                if (storageSpec != null && storageSpec.storageClassName() != null) {
                pvcBuilder.editSpec().withStorageClassName(storageSpec.storageClassName()).endSpec();
                }

                wpPvc = pvcBuilder.build();
                k8sClient.persistentVolumeClaims().inNamespace(namespace).resource(wpPvc).createOr(NonDeletingOperation::update);
            }

        // Create the Wordpress StatefulSet if it doesn't exist
        StatefulSet wpStatefulSet = k8sClient.apps().statefulSets().inNamespace(namespace).withName(name).get();
        if (wpStatefulSet == null) {
            // Load the Wordpress default deployment
            wpStatefulSet = loadYaml(StatefulSet.class, "/wordpress.statefulset.yml");
            wpStatefulSet.getMetadata().setNamespace(namespace);
            wpStatefulSet.getMetadata().setName(name);
            wpStatefulSet.getMetadata().getLabels().put("app.kubernetes.io/app", name);
            wpStatefulSet.getMetadata().setOwnerReferences(Collections.singletonList(new OwnerReferenceBuilder()
                    .withUid(resource.getMetadata().getUid())
                    .withApiVersion(resource.getApiVersion())
                    .withName(name)
                    .withKind(resource.getKind())
                    .build()
            ));

            wpStatefulSet.getSpec().getSelector().getMatchLabels().put("app", name);
            wpStatefulSet.getSpec().getTemplate().getMetadata().getLabels().put("app", name);

            wpStatefulSet.getSpec().getTemplate().getSpec().getVolumes().forEach(volume -> {
                if (volume.getConfigMap() != null)
                    volume.getConfigMap().setName(volume.getConfigMap().getName().replace("wordpress-site-id", name));
                if ("wordpress-data".equals(volume.getName())) {
                    volume.setEmptyDir(null);
                    volume.setPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
                            .withClaimName(pvcName)
                            .build());
                }
            });

            wpStatefulSet.getSpec().getTemplate().getSpec().getContainers().getFirst().setEnv(Arrays.asList(
                    new EnvVar("WORDPRESS_DB_HOST", dbSpec.host() + ":" + dbSpec.port(), null),
                    new EnvVar("WORDPRESS_DB_NAME", dbSpec.name(), null),
                    new EnvVar("WORDPRESS_DB_USER", dbSpec.user(), null),
                    new EnvVar("WORDPRESS_DB_PASSWORD", null, new EnvVarSourceBuilder()
                            .withNewSecretKeyRef(dbSpec.passwordSecretKey(), dbSpec.passwordSecretName(), false)
                            .build()
                    ),
                    new EnvVar("HTTP_HOST", siteSpec.host(), null),
                    new EnvVar("WP_SITE_PATH", siteSpec.path(), null)
            ));

            // Configure the second initContainer wp-init
            if (wpStatefulSet.getSpec().getTemplate().getSpec().getInitContainers().size() > 1) {
                wpStatefulSet.getSpec().getTemplate().getSpec().getInitContainers().get(1).setEnv(Arrays.asList(
                        new EnvVar("WORDPRESS_DB_HOST", dbSpec.host() + ":" + dbSpec.port(), null),
                        new EnvVar("WORDPRESS_DB_NAME", dbSpec.name(), null),
                        new EnvVar("WORDPRESS_DB_USER", dbSpec.user(), null),
                        new EnvVar("WORDPRESS_DB_PASSWORD", null, new EnvVarSourceBuilder()
                                .withNewSecretKeyRef(dbSpec.passwordSecretKey(), dbSpec.passwordSecretName(), false)
                                .build()
                        ),
                        new EnvVar("HTTP_HOST", siteSpec.host(), null),
                        new EnvVar("WP_ADMIN_USER", siteSpec.adminEmail(), null),
                        new EnvVar("WP_ADMIN_PASSWORD", siteSpec.adminPassword(), null),
                        new EnvVar("WP_ADMIN_EMAIL", siteSpec.adminEmail(), null)
                ));
            }

            k8sClient.apps().statefulSets().inNamespace(namespace).resource(wpStatefulSet).createOr(NonDeletingOperation::update);
        } else {
            boolean needsUpdate = false;
            if (wpStatefulSet.getSpec() != null
                    && wpStatefulSet.getSpec().getTemplate() != null
                    && wpStatefulSet.getSpec().getTemplate().getSpec() != null
                    && wpStatefulSet.getSpec().getTemplate().getSpec().getVolumes() != null) {
                for (Volume volume : wpStatefulSet.getSpec().getTemplate().getSpec().getVolumes()) {
                    if ("wordpress-data".equals(volume.getName())) {
                        String currentClaim = volume.getPersistentVolumeClaim() != null
                                ? volume.getPersistentVolumeClaim().getClaimName()
                                : null;
                        if (!pvcName.equals(currentClaim)) {
                            volume.setEmptyDir(null);
                            volume.setPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
                                    .withClaimName(pvcName)
                                    .build());
                            needsUpdate = true;
                        }
                    }
                }
            }

            if (needsUpdate) {
                k8sClient.apps().statefulSets().inNamespace(namespace).resource(wpStatefulSet).createOr(NonDeletingOperation::update);
            }
        }

        return UpdateControl.noUpdate();
    }

    /**
     *  Load a YAML file and transform it to a Java class.
     * 
     * @param clazz The java class to create
     * @param yamlPath The yaml file path in the classpath
     */
    private <T> T loadYaml(Class<T> clazz, String yamlPath) {
        try (InputStream is = getClass().getResourceAsStream(yamlPath)) {
          return Serialization.unmarshal(is, clazz);
        } catch (IOException ex) {
          throw new IllegalStateException("Cannot find yaml on classpath: " + yamlPath);
        }
    }
}
